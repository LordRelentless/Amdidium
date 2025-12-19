package me.cortex.amdidium.gl.buffers;

import me.cortex.amdidium.gl.GlObject;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL44.*;

/**
 * A persistently mapped CPU-visible buffer.
 *
 * This buffer is intended for streaming uploads from the CPU to the GPU.
 * It is backend-agnostic and works on all OpenGL 4.5+ drivers (AMD, Intel, NVIDIA).
 *
 * Vulkan and DirectX backends will provide their own implementations.
 */
public class PersistentClientMappedBuffer extends GlObject implements IClientMappedBuffer {

    private final long addr;
    private final long size;

    public PersistentClientMappedBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;

        // Allocate immutable storage with persistent client mapping
        glNamedBufferStorage(
                id,
                size,
                GL_MAP_PERSISTENT_BIT |
                GL_MAP_WRITE_BIT |
                GL_CLIENT_STORAGE_BIT,
                0
        );

        // Map the buffer persistently
        addr = nglMapNamedBufferRange(
                id,
                0,
                size,
                GL_MAP_PERSISTENT_BIT |
                GL_MAP_WRITE_BIT |
                GL_MAP_UNSYNCHRONIZED_BIT |
                GL_MAP_FLUSH_EXPLICIT_BIT
        );

        if (addr == 0) {
            throw new IllegalStateException("Failed to map persistent client buffer");
        }
    }

    @Override
    public long clientAddress() {
        return addr;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void delete() {
        super.free0();
        glUnmapNamedBuffer(id);
        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        delete();
    }
}
