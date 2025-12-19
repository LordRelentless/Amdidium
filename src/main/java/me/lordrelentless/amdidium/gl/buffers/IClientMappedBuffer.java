package me.lordrelentless.amdidium.gl.buffers;

public interface IClientMappedBuffer extends Buffer {
    long clientAddress();
}
