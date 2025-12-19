package me.lordrelentless.amdidium.gl.buffers;

import me.lordrelentless.amdidium.gl.IResource;

public interface Buffer extends IResource {
    int getId();
    long getSize();
}
