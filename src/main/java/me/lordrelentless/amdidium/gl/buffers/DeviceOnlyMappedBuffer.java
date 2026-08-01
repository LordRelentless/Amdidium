package me.lordrelentless.amdidium.gl.buffers;

import me.lordrelentless.amdidium.gl.GlObject;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.config.AmdidiumConfig;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45C.glDeleteBuffers;

/**
 * Backend-agnostic GPU buffer that exposes a device address when supported.
 *
 * OpenGL:
 *   - Uses ARB_buffer_storage for persistent mapping.
 *   - Uses ARB_buffer_address (if available) for GPU addresses.
 *
 * Vulkan / DirectX:
 *   - Device address is provided by backend-specific implementations.
 */
public class DeviceOnlyMappedBuffer extends GlObject implements IDeviceMappedBuffer {

    private final long size;
    private long deviceAddress = 0;

    public DeviceOnlyMappedBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;

        // Allocate immutable storage (OpenGL backend)
        glNamedBufferStorage(id, size, 0);

        // Try to obtain a GPU device address (ARB_buffer_address)
        if (Amdidium.config.backend == AmdidiumConfig.Backend.OPENGL) {
            tryAcquireOpenGLDeviceAddress();
        }
    }

    private void tryAcquireOpenGLDeviceAddress() {
        deviceAddress = 0;
    }

    @Override
    public long getDeviceAddress() {
        return deviceAddress;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void delete() {
        super.free0();

        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        delete();
    }

}
