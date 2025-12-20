package me.lordrelentless.amdidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.lordrelentless.amdidium.config.AmdidiumConfig;
import me.lordrelentless.amdidium.config.StatisticsLoggingLevel;
import me.lordrelentless.amdidium.config.TranslucencySortingLevel;
import me.lordrelentless.amdidium.gl.RenderDevice;
import me.lordrelentless.amdidium.gl.buffers.IDeviceMappedBuffer;
import me.lordrelentless.amdidium.managers.RegionManager;
import me.lordrelentless.amdidium.managers.RegionVisibilityTracker;
import me.lordrelentless.amdidium.managers.SectionManager;
import me.lordrelentless.amdidium.renderers.*;
import me.lordrelentless.amdidium.util.DownloadTaskStream;
import me.lordrelentless.amdidium.util.TickableManager;
import me.lordrelentless.amdidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;
import java.util.List;

import static me.lordrelentless.amdidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.*;

public class RenderPipeline {

    public enum Backend {
        OPENGL,
        VULKAN,
        DIRECTX
    }

    // Detect NV mesh shader support
    public static final boolean IS_NVIDIA =
            org.lwjgl.opengl.GL.getCapabilities().GL_NV_mesh_shader &&
            org.lwjgl.opengl.GL.getCapabilities().GL_NV_vertex_buffer_unified_memory;

    private final Backend backend;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(
            4 * 4 * 4 + // projection * modelView matrix
            4 * 4 +     // inverse matrix (optional, fog)
            4 * 4 +     // chunkPos
            4 + 4 * 4 + // delta + padding
            4 * 4 +     // fog color
            8 * 8 +     // addresses
            3 * 4 + 3 + // screen size + padding
            4 + 8 + 8 + // fog params, visibleRegions, frameId
            (4 * 4 * 4),// extra matrix / reserved
            2
    );

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
    private final IDeviceMappedBuffer regionSortingList;
    private final IDeviceMappedBuffer statisticsBuffer;
    private final IDeviceMappedBuffer transformationArray;
    private final IDeviceMappedBuffer originOffsetArray;

    // NEW: AMD/GL indirect command buffer (20 bytes per region)
    private final IDeviceMappedBuffer indirectCommandBuffer;

    private final BitSet regionVisibilityTracker;
    private final IntSet regionsToSort = new IntOpenHashSet();

    private static final class Statistics {
        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
    }

    private final Statistics stats;

    private int prevRegionCount;
    private int frameId;
    private boolean compiledForFog = false;

    public RenderPipeline(RenderDevice device,
                          UploadingBufferStream uploadStream,
                          DownloadTaskStream downloadStream,
                          SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;

        this.backend = Backend.OPENGL;
        this.compiledForFog = AmdidiumConfig.render_fog;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();

        int maxRegions = sectionManager.getRegionManager().maxRegions();

        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions * 2L);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L); // NV path
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L); // NV path
        indirectCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 20L); // AMD path
        regionSortingList = device.createDeviceOnlyMappedBuffer(maxRegions * 2L);
        this.transformationArray = device.createDeviceOnlyMappedBuffer(
                RegionManager.MAX_TRANSFORMATION_COUNT * (4 * 4 * 4)
        );
        this.originOffsetArray = device.createDeviceOnlyMappedBuffer(
                RegionManager.MAX_TRANSFORMATION_COUNT * 8
        );

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = device.createDeviceOnlyMappedBuffer(4 * 4);
        stats = new Statistics();

        // Initialize transformation array to identity
        {
            long ptr = this.uploadStream.upload(
                    this.transformationArray,
                    0,
                    RegionManager.MAX_TRANSFORMATION_COUNT * (4 * 4 * 4)
            );
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4 * 4 * 4;
            }
        }

        nglClearNamedBufferData(
                this.originOffsetArray.getId(),
                GL_R8UI,
                GL_RED_INTEGER,
                GL_UNSIGNED_BYTE,
                0
        );
    }

    private void renderFrameOpenGL(int visibleRegions,
                                   int regionSortSize,
                                   short[] regionMap,
                                   int DEBUG_RENDER_LEVEL,
                                   boolean WRITE_DEPTH) {

        glBindBufferRange(GL_UNIFORM_BUFFER, 0, sceneUniform.getId(), 0, SCENE_SIZE);

        // Fill AMD indirect command buffer
        if (!IS_NVIDIA && visibleRegions > 0) {
            long addr = uploadStream.upload(indirectCommandBuffer, 0, visibleRegions * 20L);
            for (int i = 0; i < visibleRegions; i++) {
                int regionId = regionMap[i];

                // Placeholder values — replace with actual index buffer layout
                int indicesPerRegion = 6; // two triangles per quad
                int firstIndex = regionId * indicesPerRegion;
                int baseVertex = 0;

                MemoryUtil.memPutInt(addr, indicesPerRegion); addr += 4;
                MemoryUtil.memPutInt(addr, 1);                addr += 4; // one instance
                MemoryUtil.memPutInt(addr, firstIndex);       addr += 4;
                MemoryUtil.memPutInt(addr, baseVertex);       addr += 4;
                MemoryUtil.memPutInt(addr, regionId);         addr += 4;
            }
        }

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(prevRegionCount,
                                     terrainCommandBuffer.getDeviceAddress(),
                                     indirectCommandBuffer.getId(),
                                     IS_NVIDIA);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL != 1) {
            glColorMask(false, false, false, false);
        }

        regionRasterizer.raster(visibleRegions,
                                indirectCommandBuffer.getId(),
                                IS_NVIDIA);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        sectionRasterizer.raster(visibleRegions,
                                 indirectCommandBuffer.getId(),
                                 IS_NVIDIA);

        glDepthMask(true);
        glColorMask(true, true, true, true);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        if (AmdidiumConfig.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_B
