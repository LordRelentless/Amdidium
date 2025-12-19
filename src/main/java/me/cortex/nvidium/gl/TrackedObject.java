package me.cortex.amdidium.gl;

import me.cortex.amdidium.Amdidium;

import java.lang.ref.Cleaner;

/**
 * Base class for all GPU-backed resources across all backends.
 *
 * Tracks:
 *  - whether the resource was freed
 *  - double-free errors
 *  - unfreed resources (with optional debug trace)
 *
 * This class is backend-agnostic and used by OpenGL, Vulkan, and DirectX objects.
 */
public abstract class TrackedObject {

    private final Ref ref;

    public TrackedObject() {
        this.ref = register(this);
    }

    /**
     * Marks the object as freed and runs the cleaner.
     * Subclasses should call this before releasing backend resources.
     */
    protected void free0() {
        if (this.isFreed()) {
            throw new IllegalStateException("Object " + this + " was double freed.");
        }
        this.ref.freedRef[0] = true;
        this.ref.cleanable.clean();
    }

    public abstract void free();

    public void assertNotFreed() {
        if (isFreed()) {
            throw new IllegalStateException("Object " + this + " should not be free, but is");
        }
    }

    public boolean isFreed() {
        return this.ref.freedRef[0];
    }

    public record Ref(Cleaner.Cleanable cleanable, boolean[] freedRef) {}

    private static final Cleaner cleaner = Cleaner.create();

    public static Ref register(Object obj) {
        String clazz = obj.getClass().getName();

        Throwable trace = Amdidium.IS_DEBUG ? new Throwable() : null;

        boolean[] freed = new boolean[1];

        var clean = cleaner.register(obj, () -> {
            if (!freed[0]) {
                System.err.println("Object named: " + clazz + " was not freed, location at:\n");
                if (trace != null) {
                    trace.printStackTrace();
                } else {
                    System.err.println("Unknown location, enable debug mode");
                }
                System.err.flush();
            }
        });

        return new Ref(clean, freed);
    }
}
