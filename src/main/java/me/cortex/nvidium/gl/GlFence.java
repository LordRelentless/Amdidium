package me.cortex.amdidium.gl;

import static org.lwjgl.opengl.GL32.*;

/**
 * OpenGL GPU fence synchronization primitive.
 *
 * This is the OpenGL backend implementation used by Amdidium.
 * Vulkan and DirectX backends will provide their own equivalents.
 */
public class GlFence extends TrackedObject {

    private final long fence;
    private boolean signaled;

    public GlFence() {
        this.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public boolean signaled() {
        this.assertNotFreed();

        if (!this.signaled) {
            int ret = glClientWaitSync(this.fence, 0, 0);

            if (ret == GL_ALREADY_SIGNALED || ret == GL_CONDITION_SATISFIED) {
                this.signaled = true;
            } else if (ret != GL_TIMEOUT_EXPIRED) {
                throw new IllegalStateException(
                        "Poll for fence failed, ret: " + ret + " glError: " + glGetError()
                );
            }
        }

        return this.signaled;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteSync(this.fence);
    }
}
