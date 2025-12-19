package me.cortex.amdidium;

import me.cortex.amdidium.gl.RenderDevice;
import me.cortex.amdidium.managers.AsyncOcclusionTracker;
import me.cortex.amdidium.managers.SectionManager;
import me.cortex.amdidium.sodiumCompat.AmdidiumCompactChunkVertex;
import me.cortex.amdidium.util.DownloadTaskStream;
import me.cortex.amdidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.Sprite;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.ATIMeminfo.GL_VBO_FREE_MEMORY_ATI;

public class AmdidiumWorldRenderer {
    private static final RenderDevice device = new RenderDevice();

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;
    private final RenderPipeline renderPipeline;

    private final AsyncOcclusionTracker asyncChunkTracker;

    // Max memory that the GPU can use to store geometry in MB
    private long max_geometry_memory;
    private long last_sample_time;

    // Note: the reason that asyncChunkTracker is passed in as an already constructed object
    // is because of the number of arguments it takes to construct it.
    public AmdidiumWorldRenderer(AsyncOcclusionTracker asyncChunkTracker) {
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit + 1;

        // 32 MB upload buffer
        this.uploadStream = new UploadingBufferStream(device, 32 * 1024 * 1024);
        // 8 MB download buffer
        this.downloadStream = new DownloadTaskStream(device, frames, 8 * 1024 * 1024);

        update_allowed_memory();

        // Geometry arena size in bytes
        // Original Nvidium used CompactChunkVertex.STRIDE; our AMD path uses AmdidiumCompactChunkVertex.
        this.sectionManager = new SectionManager(
                device,
                max_geometry_memory * 1024L * 1024L,
                uploadStream,
                AmdidiumCompactChunkVertex.STRIDE,
                this
        );

        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);
        this.asyncChunkTracker = asyncChunkTracker;
    }

    public void enqueueRegionSort(int regionId) {
        this.renderPipeline.enqueueRegionSort(regionId);
    }

    public void delete() {
        uploadStream.delete();
        downloadStream.delete();
        renderPipeline.delete();

        if (asyncChunkTracker != null) {
            asyncChunkTracker.delete();
        }

        sectionManager.destroy();
    }

    public void reloadShaders() {
        renderPipeline.reloadShaders();
    }

    public void renderFrame(Viewport viewport, ChunkRenderMatrices matrices, double x, double y, double z) {
        renderPipeline.renderFrame(viewport, matrices, x, y, z);

        while (sectionManager.terrainAreana.getUsedMB() > (max_geometry_memory - 100)) {
            renderPipeline.removeARegion();
        }

        if (Amdidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER &&
                (System.currentTimeMillis() - last_sample_time) > 60_000) {
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
        }
    }

    public void renderTranslucent() {
        this.renderPipeline.renderTranslucent();
    }

    public void deleteSection(RenderSection section) {
        this.sectionManager.deleteSection(section);
    }

    public void uploadBuildResult(ChunkBuildOutput buildOutput) {
        this.sectionManager.uploadChunkBuildResult(buildOutput);
    }

    public void addDebugInfo(ArrayList<String> debugInfo) {
        debugInfo.add("Using Amdidium renderer: " + Amdidium.MOD_VERSION);

        debugInfo.add(
                "Mem" + (Amdidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER ? "" : " (fallback)") +
                        ": " +
                        (Amdidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER
                                ? this.sectionManager.terrainAreana.getAllocatedMB()
                                : this.sectionManager.terrainAreana.getUsedMB()) +
                        "/" + this.max_geometry_memory +
                        String.format(", F: %.2f", sectionManager.terrainAreana.getFragmentation() * 100.0)
        );

        debugInfo.add(
                "Regions: " +
                        sectionManager.getRegionManager().regionCount() + "/" +
                        sectionManager.getRegionManager().maxRegions()
        );

        if (this.asyncChunkTracker != null) {
            debugInfo.add(
                    "A-BFS: " + asyncChunkTracker.getIterationTime() +
                            " Q: " + Arrays.toString(this.asyncChunkTracker.getBuildQueueSizes())
            );
        }

        this.renderPipeline.addDebugInfo(debugInfo);
    }

    /**
     * Updates the allowed geometry memory budget.
     *
     * For Nvidia, Nvidium used GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX (NVX extension).
     * For AMD, we prefer GL_VBO_FREE_MEMORY_ATI (GL_ATI_meminfo) when available.
     * If automatic detection is disabled or ATI_meminfo isn't usable, fall back to config.
     */
    private void update_allowed_memory() {
        if (Amdidium.config.automatic_memory) {
            long availableMb;

            try {
                // GL_ATI_meminfo returns values in KB for the *_FREE_MEMORY_ATI enums.
                int freeKb = glGetInteger(GL_VBO_FREE_MEMORY_ATI);
                availableMb = freeKb / 1024L;
            } catch (Throwable t) {
                // If the extension isn't present or querying fails, log once and fall back to config.
                Amdidium.LOGGER.warn(
                        "Failed to query AMD GPU memory via GL_ATI_meminfo; falling back to configured max_geometry_memory",
                        t
                );
                availableMb = Amdidium.config.max_geometry_memory;
            }

            long currentlyUsedMb = (sectionManager == null)
                    ? 0
                    : sectionManager.terrainAreana.getMemoryUsed() / (1024L * 1024L);

            max_geometry_memory = availableMb + currentlyUsedMb;

            // Reserve 1 GB for everything else
            max_geometry_memory -= 1024;

            // Minimum 2 GB geometry budget
            max_geometry_memory = Math.max(2048, max_geometry_memory);
        } else {
            max_geometry_memory = Amdidium.config.max_geometry_memory;
        }
    }

    public void update(Camera camera, Viewport viewport, int frame, boolean spectator) {
        if (asyncChunkTracker != null) {
            asyncChunkTracker.update(viewport, camera, spectator);
        }
    }

    public int getAsyncFrameId() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getFrame();
        } else {
            return -1;
        }
    }

    public List<RenderSection> getSectionsWithEntities() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getLatestSectionsWithEntities();
        } else {
            return List.of();
        }
    }

    public SectionManager getSectionManager() {
        return sectionManager;
    }

    @Nullable
    public Sprite[] getAnimatedSpriteSet() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getVisibleAnimatedSprites();
        } else {
            return new Sprite[0];
        }
    }

    public void setTransformation(int id, Matrix4fc transform) {
        this.renderPipeline.setTransformation(id, transform);
    }

    public void setOrigin(int id, int x, int y, int z) {
        this.renderPipeline.setOrigin(id, x, y, z);
    }

    public int getAsyncBfsVisibilityCount() {
        if (this.asyncChunkTracker != null) {
            return this.asyncChunkTracker.getLastVisibilityCount();
        } else {
            return -1;
        }
    }

    public int getMaxGeometryMemory() {
        return (int) max_geometry_memory;
    }
}
