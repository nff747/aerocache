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

    public OffHeapArena(long defaultSlabSize) {
        if (defaultSlabSize <= 0) {
            throw new IllegalArgumentException("Slab size must be positive: " + defaultSlabSize);
        }
        this.slabSize = defaultSlabSize;
        allocateNewSlab(defaultSlabSize);
    }

    public synchronized long allocate(int bytes) {
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
