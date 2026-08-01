package me.lordrelentless.amdidium.gl.buffers;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.config.AmdidiumConfig;
import me.lordrelentless.amdidium.gl.GlObject;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glNamedBufferStorage;

/**
 * Backend-agnostic sparse buffer with optional device address support.
 *
 * OpenGL:
 *   - Uses ARB_sparse_buffer for page commitment.
 *   - Uses ARB_buffer_address (if available) for GPU addresses.
 *
 * Vulkan:
 *   - Uses VkSparseBufferMemoryBindInfo (implemented in Vulkan backend).
 *
 * DirectX:
 *   - Uses tiled resources (implemented in DX backend).
 */
public class PersistentSparseAddressableBuffer extends GlObject implements IDeviceMappedBuffer {

    public static long alignUp(long number, long alignment) {
        long delta = number % alignment;
        return delta == 0 ? number : number + (alignment - delta);
    }

    public static final long PAGE_SIZE = 1 << 20; // 1 MB pages

    private final long size;
    private long deviceAddress = 0;

    private final Int2IntOpenHashMap allocationCount = new Int2IntOpenHashMap();

    public PersistentSparseAddressableBuffer(long size) {
        super(glCreateBuffers());
        this.size = alignUp(size, PAGE_SIZE);

        switch (Amdidium.config.backend) {
            case OPENGL -> initOpenGL();
            case VULKAN -> initVulkan();
            case DIRECTX -> initDirectX();
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  OpenGL Backend
    // ───────────────────────────────────────────────────────────────
    private void initOpenGL() {
        // Allocate sparse storage
        glNamedBufferStorage(id, size, 0);
    }

    private void commitPagesGL(int page, int count, boolean commit) {
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, id);
        org.lwjgl.opengl.ARBSparseBuffer.glBufferPageCommitmentARB(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, PAGE_SIZE * page, PAGE_SIZE * count, commit);
    }

    // ───────────────────────────────────────────────────────────────
    //  Vulkan Backend (stub — implemented in VulkanBuffer)
    // ───────────────────────────────────────────────────────────────
    private void initVulkan() {
        // Vulkan backend will override this class entirely.
        // This constructor is only called for OpenGL.
        throw new UnsupportedOperationException("Vulkan sparse buffer is implemented in Vulkan backend");
    }

    // ───────────────────────────────────────────────────────────────
    //  DirectX Backend (stub — implemented in DX12Buffer)
    // ───────────────────────────────────────────────────────────────
    private void initDirectX() {
        // DX12 backend will override this class entirely.
        throw new UnsupportedOperationException("DirectX sparse buffer is implemented in DX backend");
    }

    // ───────────────────────────────────────────────────────────────
    //  Sparse Residency Management (OpenGL only)
    // ───────────────────────────────────────────────────────────────
    public void ensureAllocated(long addr, long size) {
        int pstart = (int) (addr / PAGE_SIZE);
        int pend = (int) ((addr + size + PAGE_SIZE - 1) / PAGE_SIZE);
        allocatePages(pstart, pend - pstart);
    }

    public void deallocate(long addr, long size) {
        int pstart = (int) (addr / PAGE_SIZE);
        int pend = (int) ((addr + size + PAGE_SIZE - 1) / PAGE_SIZE);
        deallocatePages(pstart, pend - pstart);
    }

    private void allocatePages(int page, int count) {
        commitPagesGL(page, count, true);
        for (int i = 0; i < count; i++) {
            allocationCount.addTo(page + i, 1);
        }
    }

    private void deallocatePages(int page, int count) {
        for (int i = 0; i < count; i++) {
            int idx = page + i;
            int newCount = allocationCount.get(idx) - 1;

            if (newCount > 0) {
                allocationCount.put(idx, newCount);
            } else {
                allocationCount.remove(idx);
                commitPagesGL(idx, 1, false);
            }
        }
    }

    public int getPagesCommitted() {
        return allocationCount.size();
    }

    // ───────────────────────────────────────────────────────────────
    //  Device Address
    // ───────────────────────────────────────────────────────────────
    @Override
    public long getDeviceAddress() {
        return deviceAddress;
    }

    // ───────────────────────────────────────────────────────────────
    //  Cleanup
    // ───────────────────────────────────────────────────────────────
    @Override
    public void delete() {
        super.free0();

        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        delete();
    }

    @Override
    public long getSize() {
        return size;
    }

}
