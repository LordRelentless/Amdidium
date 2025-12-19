package me.lordrelentless.amdidium.renderers;

import me.lordrelentless.amdidium.gl.shader.Shader;
import me.lordrelentless.amdidium.sodiumCompat.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.util.Identifier;

import static me.lordrelentless.amdidium.gl.shader.ShaderType.FRAGMENT;
import static me.lordrelentless.amdidium.gl.shader.ShaderType.MESH;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;

public class RegionRasterizer extends Phase {
    private final Shader shader = Shader.make()
            .addSource(MESH, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/region_raster/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("amdidium", "occlusion/region_raster/fragment.frag")))
            .compile();

    public void raster(int regionCount) {
        shader.bind();
        glDrawMeshTasksNV(0, regionCount);
    }

    public void delete() {
        shader.delete();
    }
}
