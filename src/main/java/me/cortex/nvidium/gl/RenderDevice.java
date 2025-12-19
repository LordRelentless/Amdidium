package me.cortex.amdidium.gl;

import me.cortex.amdidium.gl.buffers.*;

public interface RenderDevice {

    PersistentClientMappedBuffer createClientMappedBuffer(long size);

    PersistentSparseAddressableBuffer createSparseBuffer(long totalSize);

    IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size);

    void flush(IClientMappedBuffer buffer, long offset, int size);

    void barrier(int flags);

    void copyBuffer(Buffer src, Buffer dst, long srcOffset, long dstOffset, long size);
}
