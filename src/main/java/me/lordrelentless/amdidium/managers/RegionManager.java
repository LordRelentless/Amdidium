package me.lordrelentless.amdidium.managers;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.gl.RenderDevice;
import me.lordrelentless.amdidium.gl.buffers.IDeviceMappedBuffer;
import me.lordrelentless.amdidium.util.IdProvider;
import me.lordrelentless.amdidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

// 8x4x8 region grid
public class RegionManager {

    public static final int MAX_TRANSFORMATION_SIZE_BITS = 10;
    public static final int MAX_TRANSFORMATION_COUNT = (1 << MAX_TRANSFORMATION_SIZE_BITS);

    private static final boolean SAFETY_CHECKS = Amdidium.IS_DEBUG;
    public static final int META_SIZE = 16;

    private static final int TOTAL_SECTION_META_SIZE = SectionManager.SECTION_SIZE * 256;

    private final IDeviceMappedBuffer regionBuffer;
    private final IDeviceMappedBuffer sectionBuffer;
    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;

    private final Long2IntOpenHashMap regionTransformationIdMapping = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap regionMap = new Long2IntOpenHashMap();
    private final IdProvider idProvider = new IdProvider();
    private final Region[] regions;

    private final ArrayDeque<Region> dirtyRegions = new ArrayDeque<>();

    private final Consumer<Integer> regionUploadCallback;

    public RegionManager(RenderDevice device,
                         int maxRegions,
                         int maxSections,
                         UploadingBufferStream uploadStream,
                         Consumer<Integer> regionUploaded) {

        this.regionMap.defaultReturnValue(-1);
        this.device = device;

        this.regionBuffer = device.createDeviceOnlyMappedBuffer((long) maxRegions * META_SIZE);
        this.sectionBuffer = device.createDeviceOnlyMappedBuffer((long) maxSections * SectionManager.SECTION_SIZE);

        this.uploadStream = uploadStream;
        this.regions = new Region[maxRegions];
        this.regionUploadCallback = regionUploaded;
    }

    public void delete() {
        this.regionBuffer.delete();
        this.sectionBuffer.delete();
    }

    // Commit all pending region changes to the GPU
    public void commitChanges() {
        if (this.dirtyRegions.isEmpty())
            return;

        while (!this.dirtyRegions.isEmpty()) {
            var region = this.dirtyRegions.pop();
            region.isDirty = false;

            if (region.isRemoved) {
                if (this.regions[region.id] == null) {
                    long regionUpload = this.uploadStream.upload(this.regionBuffer,
                            (long) region.id * META_SIZE, META_SIZE);
                    MemoryUtil.memSet(regionUpload, -1, META_SIZE);

                    long sectionUpload = this.uploadStream.upload(this.sectionBuffer,
                            (long) region.id * TOTAL_SECTION_META_SIZE,
                            TOTAL_SECTION_META_SIZE);
                    MemoryUtil.memSet(sectionUpload, 0, TOTAL_SECTION_META_SIZE);
                }
            } else {
                long regionUpload = this.uploadStream.upload(this.regionBuffer,
                        (long) region.id * META_SIZE, META_SIZE);
                this.setRegionMetadata(regionUpload, region);

                long sectionUpload = this.uploadStream.upload(this.sectionBuffer,
                        (long) region.id * TOTAL_SECTION_META_SIZE,
                        TOTAL_SECTION_META_SIZE);
                MemoryUtil.memCopy(region.sectionData, sectionUpload, TOTAL_SECTION_META_SIZE);

                this.regionUploadCallback.accept(region.id);
            }
        }
    }

    private void setRegionMetadata(long upload, Region region) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int lastIdx = 0;

        for (int i = 0; i < 256; i++) {
            if (region.pos2id[i] == -1) continue;

            int x = i & 7;
            int y = i >>> 6;
            int z = (i >>> 3) & 7;

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);

            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);

            lastIdx = i;
        }

        long size = (long) (maxY - minY) << 62
                | (long) (maxX - minX) << 59
                | (long) (maxZ - minZ) << 56;

        long count = (long) lastIdx << 48;

        long x = ((((long) region.rx << 3) + minX) & ((1 << 24) - 1)) << 24;
        long y = ((((long) region.ry << 2) + minY) & ((1 << 24) - 1));
        long z = ((((long) region.rz << 3) + minZ) & ((1 << 24) - 1)) << (64 - 24);

        long transformationId =
                ((long) region.transformationId) << (64 - 24 - MAX_TRANSFORMATION_SIZE_BITS);

        MemoryUtil.memPutLong(upload, size | count | x | y);
        MemoryUtil.memPutLong(upload + 8, z | transformationId);
    }

    public int getSectionRefId(int section) {
        var region = this.regions[section >>> 8];
        int id = region.pos2id[section & 0xFF];
        if (id < 0 || id >= 256) throw new IllegalStateException();
        return id;
    }

    public long setSectionData(int sectionId) {
        var region = this.regions[sectionId >>> 8];
        sectionId &= 0xFF;
        sectionId = region.pos2id[sectionId];

        if (sectionId < 0 || sectionId >= 256) throw new IllegalStateException();

        this.markDirty(region);
        return region.sectionData + (sectionId * SectionManager.SECTION_SIZE);
    }

    public void removeSection(int sectionId) {
        if (sectionId < 0) {
            return;
        }
    }

    public int allocateSection(int sectionX, int sectionY, int sectionZ) {
        return 0;
    }

    private void markDirty(Region region) {
        if (region.isDirty) return;
        region.isDirty = true;
        this.dirtyRegions.add(region);
    }

    public int regionCount() {
        return this.regionMap.size();
    }

    public int maxRegions() {
        return this.regions.length;
    }

    public int maxRegionIndex() {
        return this.idProvider.maxIndex();
    }

    public boolean regionExists(int regionId) {
        return this.regions[regionId] != null;
    }

    public boolean isRegionVisible(Viewport frustum, int regionId) {
        var region = this.regions[regionId];
        if (region == null) return false;

        return frustum.isBoxVisible(
                (region.rx << 7) + (1 << 6),
                (region.ry << 6) + (1 << 5),
                (region.rz << 7) + (1 << 6),
                1 << 6, 1 << 5, 1 << 6
        );
    }

    public int distance(int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        return 0;
    }

    public boolean withinSquare(int dist, int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        return dist >= 0;
    }

    public boolean isRegionInACameraAxis(int regionId, double camX, double camY, double camZ) {
        return true;
    }

    public long getRegionBufferAddress() {
        return this.regionBuffer.getDeviceAddress();
    }

    public long getSectionBufferAddress() {
        return this.sectionBuffer.getDeviceAddress();
    }

    public long regionIdToKey(int regionId) {
        if (this.regions[regionId] == null) throw new IllegalStateException();
        return this.regions[regionId].key;
    }

    public void setRegionTransformId(int x, int y, int z, int id) {
        // No-op placeholder for now.
    }

    private static class Region {
        private final int rx, ry, rz;
        private final long key;
        private final int id;

        public int transformationId = 0;

        private int count;
        private final int[] pos2id = new int[256];
        private final int[] id2pos = new int[256];

        private boolean isDirty;
        private boolean isRemoved;

        private final long sectionData =
                MemoryUtil.nmemAlloc(8 * 4 * 8 * SectionManager.SECTION_SIZE);

                private Region(int id, int rx, int ry, int rz) {
            Arrays.fill(this.pos2id, -1);
            Arrays.fill(this.id2pos, -1);

            MemoryUtil.memSet(sectionData, 0, 256 * SectionManager.SECTION_SIZE);

            this.key = ChunkSectionPos.asLong(rx, ry, rz);
            this.id = id;

            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }

        public void delete() {
            MemoryUtil.nmemFree(this.sectionData);
        }

        public void verifyIntegrity() {
            if (!SAFETY_CHECKS) return;

            for (int i = 0; i < 256; i++) {
                if (this.id2pos[i] != -1 && this.pos2id[this.id2pos[i]] != i)
                    throw new IllegalStateException();

                if (this.pos2id[i] != -1 && this.id2pos[this.pos2id[i]] != i)
                    throw new IllegalStateException();
            }
        }
    }

    public void destroy() {
        this.sectionBuffer.delete();
        this.regionBuffer.delete();
    }

    // === NEW HELPER METHODS FOR INDIRECT DRAW COMMANDS ===

    /**
     * Returns the number of indices to draw for a given region.
     * Uses GeometryProfile to adapt to texture pack resolution.
     */
    public int getIndicesPerRegion(int regionId) {
        Region region = this.regions[regionId];
        if (region == null) return 0;
        int indicesPerSection = GeometryProfile.getIndicesPerSection();
        return region.count * indicesPerSection;
    }

    /**
     * Returns the starting index offset in the global index buffer
     * for a given region. If regions are packed sequentially, this
     * is simply regionId * indicesPerRegion.
     */
    public int getFirstIndex(int regionId) {
        Region region = this.regions[regionId];
        if (region == null) return 0;
        return region.id * GeometryProfile.getIndicesPerSection();
    }

    /**
     * Returns the base vertex offset for a given region.
     * If vertices are packed sequentially, this is regionId * verticesPerRegion.
     */
    public int getBaseVertex(int regionId) {
        Region region = this.regions[regionId];
        if (region == null) return 0;
        return region.id * GeometryProfile.getVerticesPerRegion();
    }

    // === GEOMETRY PROFILE HELPER ===
    public static final class GeometryProfile {

        // Define constants for different resolutions
        public static final int INDICES_PER_SECTION_16 = 6 * 16 * 16; // example
        public static final int INDICES_PER_SECTION_32 = 6 * 32 * 32;
        public static final int INDICES_PER_SECTION_64 = 6 * 64 * 64;

        public static final int VERTICES_PER_REGION_16 = 16 * 16 * 16;
        public static final int VERTICES_PER_REGION_32 = 32 * 32 * 32;
        public static final int VERTICES_PER_REGION_64 = 64 * 64 * 64;

        /**
         * Detects the current texture resolution and returns indices per section.
         */
        public static int getIndicesPerSection() {
            int resolution = detectResolution();
            return switch (resolution) {
                case 32 -> INDICES_PER_SECTION_32;
                case 64 -> INDICES_PER_SECTION_64;
                default -> INDICES_PER_SECTION_16;
            };
        }

        /**
         * Detects the current texture resolution and returns vertices per region.
         */
        public static int getVerticesPerRegion() {
            int resolution = detectResolution();
            return switch (resolution) {
                case 32 -> VERTICES_PER_REGION_32;
                case 64 -> VERTICES_PER_REGION_64;
                default -> VERTICES_PER_REGION_16;
            };
        }

        /**
         * Detects resolution based on active resource pack.
         * Placeholder: always returns 16 for vanilla.
         */
        private static int detectResolution() {
            var mc = MinecraftClient.getInstance();
            // TODO: Inspect resource pack metadata or texture size
            // For now, assume vanilla
            return 16;
        }
    }
}
