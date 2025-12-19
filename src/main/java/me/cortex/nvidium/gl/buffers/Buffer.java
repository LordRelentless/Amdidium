package me.cortex.amdidium.gl.buffers;

import me.cortex.amdidium.gl.IResource;

public interface Buffer extends IResource {
    int getId();
    long getSize();
}
