package me.lordrelentless.amdidium.gl.buffers;

import me.lordrelentless.amdidium.gl.GlObject;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglMapNamedBufferRange;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glDeleteBuffers;

/**
 * A persistently mapped CPU-visible buffer.
 *
 * This buffer is intended for streaming uploads from the CPU to the GPU.
 * It is backend-agnostic and works on all OpenGL 4.5+ drivers (AMD, Intel, NVIDIA).
 *
 * Vulkan and DirectX backends will provide their own implementations.
 */
public class PersistentClientMappedBuffer extends GlObject implements IClientMappedBuffer {

    protected final long addr;
    protected final long size;

    public PersistentClientMappedBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;

        // Allocate immutable storage with persistent client mapping
        org.lwjgl.opengl.GL45C.glNamedBufferStorage(id, size, 0);

        // Map the buffer persistently
        addr = nglMapNamedBufferRange(id, 0, size, org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT);

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
        org.lwjgl.opengl.GL30.glUnmapBuffer(org.lwjgl.opengl.GL30.GL_ARRAY_BUFFER);
        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        delete();
    }
}
