package io.aerocache.collection;

import io.aerocache.memory.OffHeapArena;
import io.aerocache.memory.UnsafeAccess;

import java.io.Closeable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-concurrency striped off-heap hash map for AeroCache.
 * Partitions data across multiple independent OffHeapHashMap instances,
 * each protected by an independent ReentrantLock to allow ultra-low latency,
 * concurrent reads and writes across multiple NIO event loops or worker threads.
 */
public final class PartitionedOffHeapHashMap implements Closeable {

    private final int numPartitions;
    private final int partitionMask;
    private final OffHeapHashMap[] partitions;
    private final OffHeapArena[] arenas;
    private final ReentrantLock[] locks;

    public PartitionedOffHeapHashMap(int partitionCount, int capacityPerPartition, long slabSizePerPartition) {
        this.numPartitions = nextPowerOfTwo(Math.max(2, partitionCount));
        this.partitionMask = this.numPartitions - 1;
        this.partitions = new OffHeapHashMap[this.numPartitions];
        this.arenas = new OffHeapArena[this.numPartitions];
        this.locks = new ReentrantLock[this.numPartitions];

        for (int i = 0; i < this.numPartitions; i++) {
            this.arenas[i] = new OffHeapArena(slabSizePerPartition);
            this.partitions[i] = new OffHeapHashMap(capacityPerPartition, this.arenas[i]);
            this.locks[i] = new ReentrantLock();
        }
    }

    private int partitionFor(long keyAddr, int keyLen) {
        long hash = UnsafeAccess.hash64(keyAddr, keyLen);
        // Spread the hash bits for partition index
        return (int) ((hash ^ (hash >>> 32)) & partitionMask);
    }

    public boolean put(long keyAddr, int keyLen, long valAddr, int valLen, long ttlMs) {
        int part = partitionFor(keyAddr, keyLen);
        ReentrantLock lock = locks[part];
        lock.lock();
        try {
            return partitions[part].put(keyAddr, keyLen, valAddr, valLen, ttlMs);
        } finally {
            lock.unlock();
        }
    }

    public boolean get(long keyAddr, int keyLen, EntryResult result) {
        int part = partitionFor(keyAddr, keyLen);
        ReentrantLock lock = locks[part];
        lock.lock();
        try {
            return partitions[part].get(keyAddr, keyLen, result);
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(long keyAddr, int keyLen) {
        int part = partitionFor(keyAddr, keyLen);
        ReentrantLock lock = locks[part];
        lock.lock();
        try {
            return partitions[part].delete(keyAddr, keyLen);
        } finally {
            lock.unlock();
        }
    }

    public boolean exists(long keyAddr, int keyLen) {
        int part = partitionFor(keyAddr, keyLen);
        ReentrantLock lock = locks[part];
        lock.lock();
        try {
            return partitions[part].exists(keyAddr, keyLen);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        int total = 0;
        for (int i = 0; i < numPartitions; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                total += partitions[i].size();
            } finally {
                lock.unlock();
            }
        }
        return total;
    }

    public int getNumPartitions() {
        return numPartitions;
    }

    public void clear() {
        for (int i = 0; i < numPartitions; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                partitions[i].clear();
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < numPartitions; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                partitions[i].close();
            } finally {
                lock.unlock();
            }
        }
    }

    private static int nextPowerOfTwo(int val) {
        int highestOne = Integer.highestOneBit(val);
        return (highestOne == val) ? val : highestOne << 1;
    }
}
