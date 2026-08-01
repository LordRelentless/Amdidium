package me.lordrelentless.amdidium;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

public final class RenderCapabilities {
    public enum Platform {
        DISABLED,
        NVIDIA,
        AMD,
        GENERIC
    }

    public static final class Capabilities {
        public final boolean meshShaders;
        public final boolean taskShaders;
        public final boolean bindlessTextures;
        public final boolean shaderBufferLoad;
        public final boolean shaderStorageBuffer;
        public final boolean sparseBuffers;
        public final boolean persistentBuffers;
        public final boolean bufferAddress;
        public final boolean gl45Compatible;
        public final String vendor;
        public final String renderer;
        public final String version;

        public Capabilities(boolean meshShaders,
                            boolean taskShaders,
                            boolean bindlessTextures,
                            boolean shaderBufferLoad,
                            boolean shaderStorageBuffer,
                            boolean sparseBuffers,
                            boolean persistentBuffers,
                            boolean bufferAddress,
                            boolean gl45Compatible,
                            String vendor,
                            String renderer,
                            String version) {
            this.meshShaders = meshShaders;
            this.taskShaders = taskShaders;
            this.bindlessTextures = bindlessTextures;
            this.shaderBufferLoad = shaderBufferLoad;
            this.shaderStorageBuffer = shaderStorageBuffer;
            this.sparseBuffers = sparseBuffers;
            this.persistentBuffers = persistentBuffers;
            this.bufferAddress = bufferAddress;
            this.gl45Compatible = gl45Compatible;
            this.vendor = vendor;
            this.renderer = renderer;
            this.version = version;
        }

        public boolean supportsMeshPath() {
            return meshShaders && taskShaders;
        }

        public boolean supportsGenericPath() {
            return gl45Compatible || shaderStorageBuffer || shaderBufferLoad;
        }
    }

    private RenderCapabilities() {
    }

    public static Capabilities detect() {
        var caps = GL.getCapabilities();
        String vendor = GL11.glGetString(GL11.GL_VENDOR);
        String renderer = GL11.glGetString(GL11.GL_RENDERER);
        String version = GL11.glGetString(GL11.GL_VERSION);

        boolean meshShaders = false;
        boolean taskShaders = false;
        boolean bindlessTextures = false;
        boolean shaderBufferLoad = false;
        boolean shaderStorageBuffer = false;
        boolean sparseBuffers = false;
        boolean persistentBuffers = false;
        boolean bufferAddress = false;
        try {
            meshShaders = org.lwjgl.opengl.GL.getCapabilities().GL_NV_mesh_shader;
            taskShaders = org.lwjgl.opengl.GL.getCapabilities().GL_NV_mesh_shader;
            bindlessTextures = org.lwjgl.opengl.GL.getCapabilities().GL_NV_bindless_texture;
            shaderBufferLoad = org.lwjgl.opengl.GL.getCapabilities().GL_NV_gpu_shader5 || org.lwjgl.opengl.GL.getCapabilities().GL_NV_shader_buffer_load;
            shaderStorageBuffer = org.lwjgl.opengl.GL.getCapabilities().GL_NV_gpu_shader5;
            sparseBuffers = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sparse_buffer || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;
            persistentBuffers = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sparse_buffer;
            bufferAddress = org.lwjgl.opengl.GL.getCapabilities().GL_NV_vertex_buffer_unified_memory || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;
        } catch (Throwable ignored) {
            // Fall back to minimal feature detection below.
        }
        boolean gl45Compatible = isGlVersionAtLeast(version, 4.5f);

        return new Capabilities(
                meshShaders,
                taskShaders,
                bindlessTextures,
                shaderBufferLoad,
                shaderStorageBuffer,
                sparseBuffers,
                persistentBuffers,
                bufferAddress,
                gl45Compatible,
                vendor,
                renderer,
                version
        );
    }

    public static Platform resolvePlatform(String vendor, Capabilities capabilities) {
        String identity = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        if (identity.contains("nvidia") || identity.contains("geforce") || identity.contains("quadro")) {
            return Platform.NVIDIA;
        }
        if (identity.contains("amd") || identity.contains("ati") || identity.contains("advanced micro devices")) {
            return Platform.AMD;
        }
        if (capabilities.supportsMeshPath()) {
            return Platform.GENERIC;
        }
        return Platform.GENERIC;
    }

    public static boolean isCompatible(Capabilities capabilities) {
        return capabilities.gl45Compatible || capabilities.supportsMeshPath() || capabilities.supportsGenericPath();
    }

    private static boolean isGlVersionAtLeast(String version, float minimum) {
        if (version == null || version.isBlank()) {
            return false;
        }

        String[] parts = version.split("[\\D]+", 3);
        if (parts.length < 2) {
            return false;
        }

        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 4 || (major == 4 && minor >= minimum);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
