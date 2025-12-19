package me.lordrelentless.amdidium.gl.opengl.nv;

import me.lordrelentless.amdidium.gl.RenderDevice;
import me.lordrelentless.amdidium.gl.buffers.Buffer;
import me.lordrelentless.amdidium.gl.shader.Shader;
import me.lordrelentless.amdidium.sodiumCompat.ShaderLoader;
import me.lordrelentless.amdidium.util.DownloadTaskStream;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

import static me.lordrelentless.amdidium.gl.shader.ShaderType.FRAGMENT;
import static me.lordrelentless.amdidium.gl.shader.ShaderType.MESH;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;

public class RegionVisibilityTrackerGL_NV {

    private final Shader shader = Shader.make()
            .addSource(MESH, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/queries/region/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/queries/region/fragment.frag")))
            .compile();

    private final DownloadTaskStream downStream;
    private final int[] frustum;
    private final int[] visible;

    private int frame = 0;

    public RegionVisibilityTrackerGL_NV(DownloadTaskStream downStream, int maxRegions) {
        this.downStream = downStream;
        this.visible = new int[maxRegions];
        this.frustum = new int[maxRegions];
    }

    public void computeVisibility(int regionCount, Buffer regionVisibilityBuffer, short[] regionMapping) {
        shader.bind();
        frame++;

        glDrawMeshTasksNV(0, regionCount);
        org.lwjgl.opengl.GL42.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        downStream.download(regionVisibilityBuffer, 0, regionCount, ptr -> {
            for (int i = 0; i < regionMapping.length; i++) {
                if (MemoryUtil.memGetByte(ptr + i) == 1) {
                    frustum[regionMapping[i]]++;
                    visible[regionMapping[i]] = frame;
                } else {
                    frustum[regionMapping[i]]++;
                }
            }
        });
    }

    public void delete() {
        shader.delete();
    }

    public void resetRegion(int id) {
        frustum[id] = 0;
        visible[id] = 0;
    }

    public int findMostLikelyLeastSeenRegion(int maxIndex) {
        int maxRank = Integer.MIN_VALUE;
        int id = -1;

        for (int i = 0; i < maxIndex; i++) {
            if (frustum[i] <= 200) continue;

            int rank = -visible[i];
            if (rank > maxRank) {
                maxRank = rank;
                id = i;
            }
        }

        return id;
    }
}
