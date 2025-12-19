package me.lordrelentless.amdidium.gl.buffers;

import me.lordrelentless.amdidium.gl.GlObject;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.config.AmdidiumConfig;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.ARBBufferStorage.*;

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
        glNamedBufferStorage(id,
                size,
                GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_READ_BIT | GL_MAP_WRITE_BIT,
                0
        );

        // Try to obtain a GPU device address (ARB_buffer_address)
        if (Amdidium.config.backend == AmdidiumConfig.Backend.OPENGL) {
            tryAcquireOpenGLDeviceAddress();
        }
    }

    private void tryAcquireOpenGLDeviceAddress() {
        // Check if ARB_buffer_address is supported
        if (!glGetStringiSupported("GL_ARB_buffer_address")) {
            // No device address available on this backend
            deviceAddress = 0;
            return;
        }

        // Query GPU address
        long[] out = new long[1];
        glGetNamedBufferParameterui64vARB(id, GL_BUFFER_GPU_ADDRESS_ARB, out);
        glMakeNamedBufferResidentARB(id, GL_READ_WRITE);

        deviceAddress = out[0];
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

        // If ARB_buffer_address was used, make non-resident
        if (deviceAddress != 0) {
            try {
                glMakeNamedBufferNonResidentARB(id);
            } catch (Throwable ignored) {
                // Backend may not support ARB_buffer_address
            }
        }

        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        delete();
    }

    /**
     * Utility: check if an OpenGL extension is supported.
     */
    private static boolean glGetStringiSupported(String ext) {
        int count = glGetInteger(GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            String e = glGetStringi(GL_EXTENSIONS, i);
            if (ext.equals(e)) return true;
        }
        return false;
    }
}
