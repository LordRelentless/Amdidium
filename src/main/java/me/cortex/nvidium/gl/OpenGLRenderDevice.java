package me.cortex.amdidium.gl.opengl;

import me.cortex.amdidium.gl.RenderDevice;
import me.cortex.amdidium.gl.GlObject;
import me.cortex.amdidium.gl.buffers.*;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;

public class OpenGLRenderDevice implements RenderDevice {

    @Override
    public PersistentClientMappedBuffer createClientMappedBuffer(long size) {
        return new PersistentClientMappedBuffer(size);
    }

    @Override
    public PersistentSparseAddressableBuffer createSparseBuffer(long totalSize) {
        return new PersistentSparseAddressableBuffer(totalSize);
    }

    @Override
    public IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size) {
        return new DeviceOnlyMappedBuffer(size);
    }

    @Override
    public void flush(IClientMappedBuffer buffer, long offset, int size) {
        int id = ((GlObject) buffer).getId();
        glFlushMappedNamedBufferRange(id, offset, size);
    }

    @Override
    public void barrier(int flags) {
        glMemoryBarrier(flags);
    }

    @Override
    public void copyBuffer(Buffer src, Buffer dst, long srcOffset, long dstOffset, long size) {
        glCopyNamedBufferSubData(
                ((GlObject) src).getId(),
                ((GlObject) dst).getId(),
                srcOffset,
                dstOffset,
                size
        );
    }
}
