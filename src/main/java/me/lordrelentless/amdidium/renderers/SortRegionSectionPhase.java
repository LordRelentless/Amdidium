package me.lordrelentless.amdidium.renderers;

import com.mojang.blaze3d.platform.GlStateManager;
import me.lordrelentless.amdidium.gl.shader.Shader;
import me.lordrelentless.amdidium.mixin.minecraft.LightMapAccessor;
import me.lordrelentless.amdidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;

import static me.lordrelentless.amdidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class SortRegionSectionPhase extends Phase {
    private final Shader shader = Shader.make()
            .addSource(COMPUTE, ShaderLoader.parse(Identifier.of("amdidium", "sorting/region_section_sorter.comp")))
            .compile();

    public SortRegionSectionPhase() {
    }

    public void dispatch(int sortingRegionCount) {
        shader.bind();
        glDispatchCompute(sortingRegionCount, 1, 1);
    }

    public void delete() {
        shader.delete();
    }
}
