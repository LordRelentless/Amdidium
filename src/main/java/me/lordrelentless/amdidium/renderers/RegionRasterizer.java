package me.lordrelentless.amdidium.renderers;

import me.lordrelentless.amdidium.gl.shader.Shader;
import me.lordrelentless.amdidium.sodiumCompat.ShaderLoader;
import net.minecraft.util.Identifier;

import static me.lordrelentless.amdidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;

public class RegionRasterizer extends Phase {
    private final Shader shaderNV = Shader.make()
            .addSource(MESH, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/region_raster/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/region_raster/fragment.frag")))
            .compile();

    private final Shader shaderGL = Shader.make()
            .addSource(VERTEX, ShaderLoader.parse(Identifier.of("amdidium", "terrain/translucent/vertex.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/region_raster/fragment.frag")))
            .compile();

    public void raster(int regionCount, int commandBufferId, boolean isNvidia) {
        Shader shader = isNvidia ? shaderNV : shaderGL;
        shader.bind();

        if (isNvidia) {
            glDrawMeshTasksNV(0, regionCount);
        } else {
            org.lwjgl.opengl.GL45C.glBindBuffer(org.lwjgl.opengl.GL45C.GL_DRAW_INDIRECT_BUFFER, commandBufferId);
            org.lwjgl.opengl.GL43C.glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, regionCount, 20);
        }
    }

    public void delete() {
        shaderNV.delete();
        shaderGL.delete();
    }
}
