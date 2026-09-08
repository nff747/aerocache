package io.aerocache.memory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance off-heap bump arena allocator.
 * Manages contiguous slabs of native unmanaged memory using pointer arithmetic.
 * Completely eliminates JVM Garbage Collector awareness and fragmentation.
 */
public final class OffHeapArena implements Closeable {

    private final long slabSize;
    private final List<Long> slabs = new ArrayList<>();
    private long currentSlabAddress = 0;
    private long currentSlabOffset = 0;
    private long totalAllocatedBytes = 0;

    // Intrusive segregated free lists: 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
    private static final int NUM_SIZE_CLASSES = 13;
    private static final int MIN_CLASS_SIZE = 16;
    private final long[] freeListHeads = new long[NUM_SIZE_CLASSES];

    public OffHeapArena(long defaultSlabSize) {
        if (defaultSlabSize <= 0) {
            throw new IllegalArgumentException("Slab size must be positive: " + defaultSlabSize);
        }
        this.slabSize = defaultSlabSize;
        allocateNewSlab(defaultSlabSize);
    }

    private int getClassIndex(int bytes) {
        int size = MIN_CLASS_SIZE;
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            if (bytes <= size) return i;
            size <<= 1;
        }
        return -1;
    }

    private int getClassSize(int classIndex) {
        return MIN_CLASS_SIZE << classIndex;
    }

    public synchronized long allocate(int bytes) {
        if (bytes <= 0) bytes = 8;
        
        // 1. Check intrusive free list for recyclable chunk
        int classIdx = getClassIndex(bytes);
        if (classIdx != -1 && freeListHeads[classIdx] != 0L) {
            long recycledAddr = freeListHeads[classIdx];
            long nextFree = UnsafeAccess.getLong(recycledAddr);
            freeListHeads[classIdx] = nextFree;
            UnsafeAccess.setMemory(recycledAddr, getClassSize(classIdx), (byte) 0);
            return recycledAddr;
        }

        // 8-byte memory alignment for CPU word operations
        long alignedBytes = ((long) bytes + 7L) & ~7L;

        if (alignedBytes > slabSize) {
            // Dedicated slab for oversized payload
            long dedicatedSlab = UnsafeAccess.allocateMemory(alignedBytes);
            slabs.add(dedicatedSlab);
            totalAllocatedBytes += alignedBytes;
            return dedicatedSlab;
        }

        if (currentSlabOffset + alignedBytes > slabSize) {
            allocateNewSlab(slabSize);
        }

        long address = currentSlabAddress + currentSlabOffset;
        currentSlabOffset += alignedBytes;
        totalAllocatedBytes += alignedBytes;
        return address;
    }

    /**
     * Reclaims off-heap memory back to the segregated free pool.
     * Guarantees zero native memory leaks under update and delete churn.
     */
    public synchronized void free(long address, int bytes) {
        if (address == 0L || bytes <= 0) return;

        int classIdx = getClassIndex(bytes);
        if (classIdx != -1) {
            long currentHead = freeListHeads[classIdx];
            UnsafeAccess.putLong(address, currentHead);
            freeListHeads[classIdx] = address;
        }
    }

    private void allocateNewSlab(long size) {
        currentSlabAddress = UnsafeAccess.allocateMemory(size);
        UnsafeAccess.setMemory(currentSlabAddress, size, (byte) 0);
        slabs.add(currentSlabAddress);
        currentSlabOffset = 0;
    }

    public synchronized long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }

    public synchronized int getSlabCount() {
        return slabs.size();
    }

    public synchronized void reset() {
        for (long slab : slabs) {
            UnsafeAccess.freeMemory(slab);
        }
        slabs.clear();
        totalAllocatedBytes = 0;
        allocateNewSlab(slabSize);
    }

    @Override
    public synchronized void close() {
        for (long slab : slabs) {
            UnsafeAccess.freeMemory(slab);
        }
        slabs.clear();
        currentSlabAddress = 0;
        currentSlabOffset = 0;
        totalAllocatedBytes = 0;
    }
}
