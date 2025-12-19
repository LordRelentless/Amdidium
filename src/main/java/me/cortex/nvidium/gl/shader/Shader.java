package me.cortex.amdidium.gl.shader;

import me.cortex.amdidium.gl.GlObject;
import org.lwjgl.opengl.GL20C;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glUseProgram;

/**
 * OpenGL shader program wrapper.
 *
 * This class is the OpenGL backend implementation for shader programs.
 * Vulkan and DirectX backends will provide their own shader pipeline objects.
 */
public class Shader extends GlObject {

    private Shader(int program) {
        super(program);
    }

    public static Builder make(IShaderProcessor processor) {
        return new Builder(processor);
    }

    public static Builder make() {
        return new Builder((type, source) -> source);
    }

    public void bind() {
        glUseProgram(id);
    }

    @Override
    public void delete() {
        super.free0();
        glDeleteProgram(id);
    }

    @Override
    public void free() {
        delete();
    }

    // ───────────────────────────────────────────────────────────────
    //  Builder
    // ───────────────────────────────────────────────────────────────
    public static class Builder {
        private final Map<ShaderType, String> sources = new HashMap<>();
        private final IShaderProcessor processor;

        private Builder(IShaderProcessor processor) {
            this.processor = processor;
        }

        public Builder addSource(ShaderType type, String source) {
            sources.put(type, processor.process(type, source));
            return this;
        }

        public Shader compile() {
            int program = GL20C.glCreateProgram();

            int[] shaders = sources.entrySet()
                    .stream()
                    .mapToInt(entry -> createShader(entry.getKey(), entry.getValue()))
                    .toArray();

            for (int shader : shaders) {
                GL20C.glAttachShader(program, shader);
            }

            GL20C.glLinkProgram(program);

            for (int shader : shaders) {
                GL20C.glDetachShader(program, shader);
                GL20C.glDeleteShader(shader);
            }

            printProgramLinkLog(program);
            verifyProgramLinked(program);

            return new Shader(program);
        }

        private static void printProgramLinkLog(int program) {
            String log = GL20C.glGetProgramInfoLog(program);
            if (!log.isEmpty()) {
                System.err.println(log);
            }
        }

        private static void verifyProgramLinked(int program) {
            int result = GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS);
            if (result != GL20C.GL_TRUE) {
                throw new RuntimeException("Shader program linking failed, see log for details");
            }
        }

        private static int createShader(ShaderType type, String src) {
            int shader = GL20C.glCreateShader(type.gl);
            GL20C.glShaderSource(shader, src);
            GL20C.glCompileShader(shader);

            String log = GL20C.glGetShaderInfoLog(shader);
            if (!log.isEmpty()) {
                System.err.println(log);
            }

            int result = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);
            if (result != GL20C.GL_TRUE) {
                GL20C.glDeleteShader(shader);
                throw new RuntimeException("Shader compilation failed, see log for details");
            }

            return shader;
        }
    }
}
