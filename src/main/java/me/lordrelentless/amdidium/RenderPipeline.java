package me.lordrelentless.amdidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.lordrelentless.amdidium.api0.AmdidiumAPI;
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

/**
 * Backend-agnostic render pipeline core.
 *
 * Current implemented backend: OPENGL
 * Future backends: VULKAN, DIRECTX
 */
public class RenderPipeline {

    public enum Backend {
        OPENGL,
        VULKAN,
        DIRECTX
    }

    // Hook for backend selection; later this can be wired to the Mod Config GUI.
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

    private final BitSet regionVisibilityTracker;

    // Set of regions that need to be sorted
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

        // For now, we always run on OPENGL inside Minecraft Java.
        // Later, this can be wired to AmdidiumConfig to choose VULKAN / DIRECTX.
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
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L);
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L);
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

        // Initialize the transformationArray buffer to the identity affine transform
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

        // Clear the origin offset
        nglClearNamedBufferData(
                this.originOffsetArray.getId(),
                GL_R8UI,
                GL_RED_INTEGER,
                GL_UNSIGNED_BYTE,
                0
        );
    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(
                this.transformationArray,
                id * (4 * 4 * 4),
                4 * 4 * 4
        );
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x & 0x1ffffff;
        pos |= ((long) (z & 0x1ffffff)) << 25;
        pos |= ((long) (y & 0x3fff)) << 50;

        MemoryUtil.memPutLong(ptr, pos);
    }

    // NOTE: regions that were in frustum but are now out of frustum must have the visibility data cleared.
    // This is due to sections being "visible" last frame without being ticked.
    public void renderFrame(Viewport frustum,
                            ChunkRenderMatrices crm,
                            double px, double py, double pz) {

        if (sectionManager.getRegionManager().regionCount() == 0) {
            return; // Nothing to render
        }

        final int DEBUG_RENDER_LEVEL = 0; // 0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        Vector3i blockPos = new Vector3i(
                (int) Math.floor(px),
                (int) Math.floor(py),
                (int) Math.floor(pz)
        );
        Vector3i chunkPos = new Vector3i(
                blockPos.x >> 4,
                blockPos.y >> 4,
                blockPos.z >> 4
        );

        int screenWidth = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
        int screenHeight = MinecraftClient.getInstance().getWindow().getFramebufferHeight();

        int visibleRegions = 0;

        var rm = sectionManager.getRegionManager();
        short[] regionMap;

        // Enqueue all the visible regions
        {
            IntSortedSet regions = new IntAVLTreeSet();
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;

                if ((Amdidium.config.region_keep_distance != 256 &&
                     Amdidium.config.region_keep_distance != 32) &&
                     !rm.withinSquare(
                             Amdidium.config.region_keep_distance + 4,
                             i,
                             chunkPos.x,
                             chunkPos.y,
                             chunkPos.z
                     )) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(frustum, i)) {
                    // Sort by distance for overdraw control; translucency buffer is written in reverse order in shaders.
                    regions.add((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z) << 16) | i);
                    visibleRegions++;
                    regionVisibilityTracker.set(i);

                    if (rm.isRegionInACameraAxis(i, px, py, pz)) {
                        regionsToSort.add(i);
                    }

                } else {
                    if (regionVisibilityTracker.get(i)) {
                        // Going from visible to non-visible; clear temporal visibility bits if enabled.
                        if (Amdidium.config.enable_temporal_coherence) {
                            nglClearNamedBufferSubData(
                                    sectionVisibility.getId(),
                                    GL_R8UI,
                                    (long) i << 8,
                                    255,
                                    GL_RED_INTEGER,
                                    GL_UNSIGNED_BYTE,
                                    0
                            );
                        }
                    }
                    regionVisibilityTracker.clear(i);
                }
            }

            regionMap = new short[regions.size()];
            if (visibleRegions == 0) return;

            long addr = uploadStream.upload(sceneUniform, SCENE_SIZE, visibleRegions * 2L);
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutShort(addr + ((long) j << 1), (short) i);
                j++;
            }

            if (Amdidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
                stats.frustumCount = regions.size();
            }
        }

        // Upload scene data
        {
            Vector3f delta = new Vector3f(
                    (float) (px - (chunkPos.x << 4)),
                    (float) (py - (chunkPos.y << 4)),
                    (float) (pz - (chunkPos.z << 4))
            );
            delta.negate();

            long addr = uploadStream.upload(sceneUniform, 0, SCENE_SIZE);

            new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta) // Translate the subchunk position
                    .getToAddress(addr);
            addr += 4 * 4 * 4;

            if (this.compiledForFog) {
                new Matrix4f(crm.projection())
                        .mul(crm.modelView())
                        .invert()
                        .getToAddress(addr);
                addr += 4 * 4 * 4;
            }

            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0)
                    .getToAddress(addr);
            addr += 16;

            new Vector4f(delta, 0.0f)
                    .getToAddress(addr);
            addr += 16;

            new Vector4f(RenderSystem.getShaderFogColor())
                    .getToAddress(addr);
            addr += 16;

            // Addresses / pointers into GPU-resident buffers
            MemoryUtil.memPutLong(addr, sceneUniform.getDeviceAddress() + SCENE_SIZE);
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getSectionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, translucencyCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionSortingList.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.transformationArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.originOffsetArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, statisticsBuffer == null ? 0 : statisticsBuffer.getDeviceAddress());
            addr += 8;

            // Screen / fog / frame info
            MemoryUtil.memPutFloat(addr, (float) screenWidth / 2.0f);
            addr += 4;
            MemoryUtil.memPutFloat(addr, (float) screenHeight / 2.0f);
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogStart());
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogEnd());
            addr += 4;
            MemoryUtil.memPutInt(addr, RenderSystem.getShaderFogShape().getId());
            addr += 4;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (frameId++));
        }

        if (Amdidium.config.translucency_sorting_level == TranslucencySortingLevel.NONE) {
            regionsToSort.clear();
        }

        int regionSortSize = this.regionsToSort.size();

        if (regionSortSize != 0) {
            long regionSortUpload = uploadStream.upload(regionSortingList, 0, regionSortSize * 2L);
            for (int region : regionsToSort) {
                MemoryUtil.memPutShort(regionSortUpload, (short) region);
                regionSortUpload += 2;
            }
            regionsToSort.clear();
        }

        sectionManager.commitChanges();
        uploadStream.commit();

        TickableManager.TickAll();

        // Backend-specific draw path
        switch (backend) {
            case OPENGL -> renderFrameOpenGL(
                    visibleRegions,
                    regionSortSize,
                    regionMap,
                    DEBUG_RENDER_LEVEL,
                    WRITE_DEPTH
            );
            case VULKAN -> {
                // TODO: Implement Vulkan backend using VK_EXT_mesh_shader, indirect draws, etc.
            }
            case DIRECTX -> {
                // TODO: Implement DirectX 12 backend using mesh shaders and indirect command buffers.
            }
        }
    }

    private void renderFrameOpenGL(int visibleRegions,
                                   int regionSortSize,
                                   short[] regionMap,
                                   int DEBUG_RENDER_LEVEL,
                                   boolean WRITE_DEPTH) {

        // Bind scene uniform as a standard UBO (backend-agnostic model)
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, sceneUniform.getId(), 0, SCENE_SIZE);

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
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

        // In the original, this used GL_REPRESENTATIVE_FRAGMENT_TEST_NV.
        // Here, we rely on depth + stencil / shader-based visibility logic instead.
        regionRasterizer.raster(visibleRegions);

        if (DEBUG_RENDER_LEVEL == 1) {
            glColorMask(false, false, false, false);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        if (DEBUG_RENDER_LEVEL == 2) {
            glColorMask(true, true, true, true);
        }
        if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
            glDepthMask(true);
        }

        sectionRasterizer.raster(visibleRegions);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        // Temporal coherence pass
        if (Amdidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
        }

        // Visibility tracking
        {
            glDepthMask(false);
            glColorMask(false, false, false, false);

            // In NV path this used representative fragment test.
            // Here we rely on depth-only passes and shader logic in regionVisibilityTracking.
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

    // Translucency is rendered in a cursed but functional way:
    // It hijacks the unused indirect command dispatch space to dispatch translucent chunks.
    public void renderTranslucent() {
        switch (backend) {
            case OPENGL -> renderTranslucentOpenGL();
            case VULKAN -> {
                // TODO: Implement Vulkan translucent pass
            }
            case DIRECTX -> {
                // TODO: Implement DirectX translucent pass
            }
        }
    }

    private void renderTranslucentOpenGL() {
        // Re-bind scene uniform in case external code changed bindings
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, sceneUniform.getId(), 0, SCENE_SIZE);

        glEnable(GL_DEPTH_TEST);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SrcFactor.SRC_ALPHA,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SrcFactor.ONE,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA
        );

        translucencyTerrainRasterizer.raster(prevRegionCount, translucencyCommandBuffer.getDeviceAddress());

        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        glDisable(GL_DEPTH_TEST);

        // Download statistics
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
