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
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.*;

public class RenderPipeline {

    public enum Backend {
        OPENGL,
        VULKAN,
        DIRECTX
    }

    public static final boolean IS_NVIDIA =
            Amdidium.PLATFORM == RenderCapabilities.Platform.NVIDIA &&
            org.lwjgl.opengl.GL.getCapabilities().GL_NV_mesh_shader &&
            org.lwjgl.opengl.GL.getCapabilities().GL_NV_vertex_buffer_unified_memory;

    public static final boolean USE_MESH_PATH =
            (Amdidium.PLATFORM == RenderCapabilities.Platform.NVIDIA || Amdidium.PLATFORM == RenderCapabilities.Platform.GENERIC) &&
            Amdidium.CAPABILITIES != null && Amdidium.CAPABILITIES.supportsMeshPath();

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
        this.compiledForFog = Amdidium.config.render_fog;

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

    public void renderFrame(Viewport frustum,
                            ChunkRenderMatrices crm,
                            double px, double py, double pz) {
        int visibleRegions = 0;
        int regionSortSize = 0;
        short[] regionMap = new short[sectionManager.getRegionManager().maxRegions()];
        int[] visible = new int[sectionManager.getRegionManager().maxRegions()];

        // Backend-specific draw path
        switch (backend) {
            case OPENGL -> renderFrameOpenGL(
                    visibleRegions,
                    regionSortSize,
                    regionMap,
                    0,
                    true
            );
            case VULKAN -> { /* TODO */ }
            case DIRECTX -> { /* TODO */ }
        }
    }

    private void renderFrameOpenGL(int visibleRegions,
                                   int regionSortSize,
                                   short[] regionMap,
                                   int DEBUG_RENDER_LEVEL,
                                   boolean WRITE_DEPTH) {

        glBindBufferRange(GL_UNIFORM_BUFFER, 0, sceneUniform.getId(), 0, SCENE_SIZE);

        // Fill AMD indirect command buffer
        if (!USE_MESH_PATH && visibleRegions > 0) {
            long addr = uploadStream.upload(indirectCommandBuffer, 0, visibleRegions * 20L);
            for (int i = 0; i < visibleRegions; i++) {
                int regionId = regionMap[i];

                // Use RegionManager helpers instead of placeholders
                int indicesPerRegion = sectionManager.getRegionManager().getIndicesPerRegion(regionId);
                int firstIndex       = sectionManager.getRegionManager().getFirstIndex(regionId);
                int baseVertex       = sectionManager.getRegionManager().getBaseVertex(regionId);

                MemoryUtil.memPutInt(addr, indicesPerRegion); addr += 4;
                MemoryUtil.memPutInt(addr, 1);                addr += 4;
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
                                     USE_MESH_PATH && IS_NVIDIA);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) glDepthMask(true);
        if (DEBUG_RENDER_LEVEL != 1) glColorMask(false, false, false, false);

        regionRasterizer.raster(visibleRegions,
                                indirectCommandBuffer.getId(),
                                USE_MESH_PATH && IS_NVIDIA);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        sectionRasterizer.raster(visibleRegions,
                                 indirectCommandBuffer.getId(),
                                 USE_MESH_PATH && IS_NVIDIA);

        glDepthMask(true);
        glColorMask(true, true, true, true);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        if (Amdidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions,
                                      terrainCommandBuffer.getDeviceAddress(),
                                      indirectCommandBuffer.getId(),
                                      USE_MESH_PATH && IS_NVIDIA);
        }

        // Visibility tracking
        {
            glDepthMask(false);
            glColorMask(false, false, false, false);

            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);

            glDepthMask(true);
            glColorMask(true, true, true, true);
        }

        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);
    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            return;
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4 * 4 * 4), 4 * 4 * 4);
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            return;
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8L, 8);
        MemoryUtil.memPutInt(ptr, x);
        MemoryUtil.memPutInt(ptr + 4, y);
        MemoryUtil.memPutInt(ptr + 8, z);
    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    public void removeARegion() {
        removeRegion(
                regionVisibilityTracking.findMostLikelyLeastSeenRegion(
                        sectionManager.getRegionManager().maxRegionIndex()
                )
        );
    }

    public void renderTranslucent() {
        switch (backend) {
            case OPENGL -> renderTranslucentOpenGL();
            case VULKAN -> { /* TODO */ }
            case DIRECTX -> { /* TODO */ }
        }
    }

    private void renderTranslucentOpenGL() {
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, sceneUniform.getId(), 0, SCENE_SIZE);

        glEnable(GL_DEPTH_TEST);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SrcFactor.SRC_ALPHA,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SrcFactor.ONE,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA
        );

        translucencyTerrainRasterizer.raster(prevRegionCount,
                                             translucencyCommandBuffer.getDeviceAddress(),
                                             indirectCommandBuffer.getId(),
                                             USE_MESH_PATH && IS_NVIDIA);

        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        glDisable(GL_DEPTH_TEST);

        if (Amdidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            downloadStream.download(statisticsBuffer, 0, 4 * 4, (addr) -> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr + 4);
                stats.quadCount = MemoryUtil.memGetInt(addr + 8);
            });
        }

        if (Amdidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            long upload = this.uploadStream.upload(statisticsBuffer, 0, 4 * 4);
            MemoryUtil.memSet(upload, 0, 4 * 4);
        }
    }

    public void delete() {
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        indirectCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Amdidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Amdidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ").append(stats.frustumCount);
            }
            if (Amdidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ").append(stats.regionCount);
            }
            if (Amdidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ").append(stats.sectionCount);
            }
            if (Amdidium.config.statistics_level.ordinal() >= StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ").append(stats.quadCount);
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
    }

    public void reloadShaders() {
        this.compiledForFog = Amdidium.config.render_fog;
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }
}
