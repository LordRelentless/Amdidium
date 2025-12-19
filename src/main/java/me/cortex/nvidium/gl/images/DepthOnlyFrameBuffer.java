package me.cortex.amdidium.gl.images;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL30C.*;

/**
 * OpenGL depth-only framebuffer.
 *
 * This is the OpenGL backend implementation used by Amdidium.
 * Vulkan and DirectX backends will provide their own equivalents.
 */
public class DepthOnlyFrameBuffer {

    public final int width;
    public final int height;

    private final int framebufferId;
    private final int depthTextureId;

    public DepthOnlyFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;

        framebufferId = glCreateFramebuffers();
        depthTextureId = glCreateTextures(GL_TEXTURE_2D);

        // Allocate depth texture
        glTextureStorage2D(depthTextureId, 1, GL_DEPTH_COMPONENT32F, width, height);

        // Attach depth texture to FBO
        glNamedFramebufferTexture(framebufferId, GL_DEPTH_ATTACHMENT, depthTextureId, 0);

        // Validate FBO
        if (glCheckNamedFramebufferStatus(framebufferId, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                    "Depth framebuffer incomplete: " +
                    glCheckFramebufferStatus(GL_FRAMEBUFFER)
            );
        }
    }

    public int getDepthTexture() {
        return depthTextureId;
    }

    public void bind(boolean setViewport) {
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, framebufferId);
        if (setViewport) {
            GlStateManager._viewport(0, 0, width, height);
        }
    }

    public void delete() {
        glDeleteFramebuffers(framebufferId);
        glDeleteTextures(depthTextureId);
    }
}
