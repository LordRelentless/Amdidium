package me.cortex.amdidium.gl;

public abstract class GlObject extends TrackedObject implements IResource {

    protected final int id;

    protected GlObject(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
