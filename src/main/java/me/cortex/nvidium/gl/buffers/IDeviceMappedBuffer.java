package me.cortex.amdidium.gl.buffers;

public interface IDeviceMappedBuffer extends Buffer {
    long getDeviceAddress();
}
