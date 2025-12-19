package me.cortex.amdidium.gl.buffers;

public interface IClientMappedBuffer extends Buffer {
    long clientAddress();
}
