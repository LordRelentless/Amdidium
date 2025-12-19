package me.lordrelentless.amdidium.managers;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.AmdidiumWorldRenderer;
import me.lordrelentless.amdidium.gl.RenderDevice;
import me.lordrelentless.amdidium.sodiumCompat.IAmdidiumWorldRendererGetter;
import me.lordrelentless.amdidium.sodiumCompat.IRepackagedResult;
import me.lordrelentless.amdidium.util.BufferArena;
import me.lordrelentless.amdidium.util.SegmentedManager;
import me.lordrelentless.amdidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

public class SectionManager {

    public static final int SECTION_SIZE = 32;

    private final RegionManager regionManager;

    private final Long2IntOpenHashMap section2id = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2terrain = new Long2IntOpenHashMap();

    public final UploadingBufferStream uploadStream;
    public final BufferArena terrainAreana;

    private final RenderDevice device;

    private final LongSet hiddenSectionKeys = new LongOpenHashSet();

    public SectionManager(RenderDevice device,
                          long fallbackMemorySize,
                          UploadingBufferStream uploadStream,
                          int quadVertexSize,
                          AmdidiumWorldRenderer worldRenderer) {

        int maxRegions = 50_000;

        this.device = device;
        this.uploadStream = uploadStream;

        this.terrainAreana = new BufferArena(device, fallbackMemorySize, quadVertexSize);
        this.regionManager = new RegionManager(
                device,
                maxRegions,
                maxRegions * 200,
                uploadStream,
                worldRenderer::enqueueRegionSort
        );

        this.section2id.defaultReturnValue(-1);
        this.section2terrain.defaultReturnValue(-1);
    }

    public void uploadChunkBuildResult(ChunkBuildOutput result) {
        var output = ((IRepackagedResult) result).getOutput();

        RenderSection section = result.render;
        long sectionKey = ChunkSectionPos.asLong(
                section.getChunkX(),
                section.getChunkY(),
                section.getChunkZ()
        );

        if (output == null || output.quads() == 0) {
            deleteSection(sectionKey);
            return;
        }

        int terrainAddress;

        {
            terrainAddress = this.section2terrain.get(sectionKey);

            if (terrainAddress != -1 &&
                    !this.terrainAreana.canReuse(terrainAddress, output.quads())) {

                this.section2terrain.remove(sectionKey);
                this.terrainAreana.free(terrainAddress);
                terrainAddress = -1;
            }

            if (terrainAddress == -1) {
                terrainAddress = this.terrainAreana.allocQuads(output.quads());
            }

            if (terrainAddress == SegmentedManager.SIZE_LIMIT) {
                Amdidium.LOGGER.error(
                        "Terrain arena critically out of memory, expect issues with chunks!! " +
                        " quad_used: " + this.terrainAreana.getUsedMB() +
                        " physically used: " + this.terrainAreana.getMemoryUsed() +
                        " limit: " +
                        ((IAmdidiumWorldRendererGetter) (SodiumWorldRenderer.instance()))
                                .getRenderer()
                                .getMaxGeometryMemory()
                );

                deleteSection(sectionKey);
                return;
            }

            this.section2terrain.put(sectionKey, terrainAddress);

            long geometryUpload = terrainAreana.upload(uploadStream, terrainAddress);
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(output.geometry().getDirectBuffer()),
                    geometryUpload,
                    output.geometry().getLength()
            );
        }

        int sectionIdx = this.section2id.computeIfAbsent(
                sectionKey,
                key -> this.regionManager.allocateSection(
                        ChunkSectionPos.unpackX(key),
                        ChunkSectionPos.unpackY(key),
                        ChunkSectionPos.unpackZ(key)
                )
        );

        long metadata = regionManager.setSectionData(sectionIdx);
        boolean hideSectionBitSet = this.hiddenSectionKeys.contains(sectionKey);

        Vector3i min = output.min();
        Vector3i size = output.size();

        int px = section.getChunkX() << 8 | size.x << 4 | min.x;
        int py = (section.getChunkY() & 0x1FF) << 8 |
                size.y << 4 |
                min.y |
                (hideSectionBitSet ? 1 << 17 : 0) |
                (regionManager.getSectionRefId(sectionIdx) << 18);

        int pz = section.getChunkZ() << 8 | size.z << 4 | min.z;
        int pw = terrainAddress;

        new Vector4i(px, py, pz, pw).getToAddress(metadata);
        metadata += 16;

        for (int i = 0; i < 4; i++) {
            int geo = Short.toUnsignedInt(output.offsets()[i * 2]) |
                    (Short.toUnsignedInt(output.offsets()[i * 2 + 1]) << 16);

            MemoryUtil.memPutInt(metadata, geo);
            metadata += 4;
        }
    }

    public void setHideBit(int x, int y, int z, boolean hide) {
        long sectionKey = ChunkSectionPos.asLong(x, y, z);

        if (hide) {
            if (!this.hiddenSectionKeys.add(sectionKey)) return;
        } else {
            if (!this.hiddenSectionKeys.remove(sectionKey)) return;
        }

        int sectionId = this.section2id.get(sectionKey);

        if (sectionId != -1) {
            long metadata = this.regionManager.setSectionData(sectionId);
            MemoryUtil.memPutInt(
                    metadata + 4,
                    (MemoryUtil.memGetInt(metadata + 4) & ~(1 << 17)) |
                            ((hide ? 1 : 0) << 17)
            );
        }
    }

    public void deleteSection(RenderSection section) {
        deleteSection(ChunkSectionPos.asLong(
                section.getChunkX(),
                section.getChunkY(),
                section.getChunkZ()
        ));
    }

    private void deleteSection(long sectionKey) {
        int sectionIdx = this.section2id.remove(sectionKey);

        if (sectionIdx != -1) {
            int terrainIndex = this.section2terrain.remove(sectionKey);

            if (terrainIndex != -1) {
                this.terrainAreana.free(terrainIndex);
            }

            this.regionManager.removeSection(sectionIdx);
        }
    }

    public void destroy() {
        this.regionManager.destroy();
        this.terrainAreana.delete();
    }

    public void commitChanges() {
        this.regionManager.commitChanges();
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public void removeRegionById(int regionId) {
        if (!this.regionManager.regionExists(regionId)) return;

        long rk = this.regionManager.regionIdToKey(regionId);

        int X = ChunkSectionPos.unpackX(rk) << 3;
        int Y = ChunkSectionPos.unpackY(rk) << 2;
        int Z = ChunkSectionPos.unpackZ(rk) << 3;

        for (int x = X; x < X + 8; x++) {
            for (int y = Y; y < Y + 4; y++) {
                for (int z = Z; z < Z + 8; z++) {
                    this.deleteSection(ChunkSectionPos.asLong(x, y, z));
                }
            }
        }
    }
}
