package io.aerocache.collection;

import io.aerocache.memory.OffHeapArena;
import io.aerocache.memory.UnsafeAccess;

import java.io.Closeable;

/**
 * High-performance off-heap hash map with linear probing open addressing.
 * Entire index table and payload values reside strictly outside the JVM heap.
 * Performs zero heap allocations during steady-state GET, SET, DEL, and EXISTS.
 */
public final class OffHeapHashMap implements Closeable {

    public static final int SLOT_SIZE = 40;
    private static final int OFFSET_HASH = 0;       // 8 bytes (long)
    private static final int OFFSET_KEY_PTR = 8;    // 8 bytes (long)
    private static final int OFFSET_KEY_LEN = 16;   // 4 bytes (int)
    private static final int OFFSET_VAL_PTR = 20;   // 8 bytes (long)
    private static final int OFFSET_VAL_LEN = 28;   // 4 bytes (int)
    private static final int OFFSET_EXPIRE_AT = 32; // 8 bytes (long)

    private static final long EMPTY_HASH = 0L;
    private static final long TOMBSTONE_HASH = -1L;
    private static final float LOAD_FACTOR = 0.65f;

    private int capacity;
    private int mask;
    private int size;
    private int tombstones;
    private int threshold;

    private long tableAddress;
    private final OffHeapArena arena;

    public OffHeapHashMap(int initialCapacity, OffHeapArena arena) {
        this.capacity = nextPowerOfTwo(Math.max(16, initialCapacity));
        this.mask = capacity - 1;
        this.threshold = (int) (capacity * LOAD_FACTOR);
        this.arena = arena;

        long tableBytes = (long) capacity * SLOT_SIZE;
        this.tableAddress = UnsafeAccess.allocateMemory(tableBytes);
        UnsafeAccess.setMemory(tableAddress, tableBytes, (byte) 0);
    }

    public boolean put(long keyAddr, int keyLen, long valAddr, int valLen, long ttlMillis) {
        if (size + tombstones >= threshold) {
            resize();
        }

        long hash = UnsafeAccess.hash64(keyAddr, keyLen);
        if (hash == EMPTY_HASH || hash == TOMBSTONE_HASH) {
            hash = 1L;
        }

        int index = (int) (hash & mask);
        int firstTombstoneIndex = -1;
        long now = System.currentTimeMillis();

        for (int i = 0; i < capacity; i++) {
            long slotAddr = tableAddress + ((long) index) * SLOT_SIZE;
            long slotHash = UnsafeAccess.getLong(slotAddr + OFFSET_HASH);

            if (slotHash == EMPTY_HASH) {
                // Key not found, insert at first tombstone or here
                int targetIndex = (firstTombstoneIndex != -1) ? firstTombstoneIndex : index;
                long targetSlotAddr = tableAddress + ((long) targetIndex) * SLOT_SIZE;

                // Allocate and copy key & value into unmanaged arena
                long persistentKeyPtr = arena.allocate(keyLen);
                UnsafeAccess.copyMemory(keyAddr, persistentKeyPtr, keyLen);

                long persistentValPtr = arena.allocate(valLen);
                UnsafeAccess.copyMemory(valAddr, persistentValPtr, valLen);

                long expireAt = (ttlMillis > 0) ? (now + ttlMillis) : 0L;

                UnsafeAccess.putLong(targetSlotAddr + OFFSET_HASH, hash);
                UnsafeAccess.putLong(targetSlotAddr + OFFSET_KEY_PTR, persistentKeyPtr);
                UnsafeAccess.putInt(targetSlotAddr + OFFSET_KEY_LEN, keyLen);
                UnsafeAccess.putLong(targetSlotAddr + OFFSET_VAL_PTR, persistentValPtr);
                UnsafeAccess.putInt(targetSlotAddr + OFFSET_VAL_LEN, valLen);
                UnsafeAccess.putLong(targetSlotAddr + OFFSET_EXPIRE_AT, expireAt);

                if (firstTombstoneIndex != -1) {
                    tombstones--;
                }
                size++;
                return true;
            }

            if (slotHash == TOMBSTONE_HASH) {
                if (firstTombstoneIndex == -1) {
                    firstTombstoneIndex = index;
                }
            } else if (slotHash == hash) {
                int existingKeyLen = UnsafeAccess.getInt(slotAddr + OFFSET_KEY_LEN);
                long existingKeyPtr = UnsafeAccess.getLong(slotAddr + OFFSET_KEY_PTR);

                if (UnsafeAccess.memoryEquals(existingKeyPtr, existingKeyLen, keyAddr, keyLen)) {
                    // Update existing key
                    long persistentValPtr = arena.allocate(valLen);
                    UnsafeAccess.copyMemory(valAddr, persistentValPtr, valLen);

                    long expireAt = (ttlMillis > 0) ? (now + ttlMillis) : 0L;

                    UnsafeAccess.putLong(slotAddr + OFFSET_VAL_PTR, persistentValPtr);
                    UnsafeAccess.putInt(slotAddr + OFFSET_VAL_LEN, valLen);
                    UnsafeAccess.putLong(slotAddr + OFFSET_EXPIRE_AT, expireAt);
                    return false;
                }
            }

            index = (index + 1) & mask;
        }

        // Table full (should never happen if load factor resizing works)
        resize();
        return put(keyAddr, keyLen, valAddr, valLen, ttlMillis);
    }

    public boolean get(long keyAddr, int keyLen, EntryResult result) {
        result.clear();
        long hash = UnsafeAccess.hash64(keyAddr, keyLen);
        if (hash == EMPTY_HASH || hash == TOMBSTONE_HASH) {
            hash = 1L;
        }

        int index = (int) (hash & mask);
        long now = System.currentTimeMillis();

        for (int i = 0; i < capacity; i++) {
            long slotAddr = tableAddress + ((long) index) * SLOT_SIZE;
            long slotHash = UnsafeAccess.getLong(slotAddr + OFFSET_HASH);

            if (slotHash == EMPTY_HASH) {
                return false;
            }

            if (slotHash == hash) {
                int existingKeyLen = UnsafeAccess.getInt(slotAddr + OFFSET_KEY_LEN);
                long existingKeyPtr = UnsafeAccess.getLong(slotAddr + OFFSET_KEY_PTR);

                if (UnsafeAccess.memoryEquals(existingKeyPtr, existingKeyLen, keyAddr, keyLen)) {
                    long expireAt = UnsafeAccess.getLong(slotAddr + OFFSET_EXPIRE_AT);
                    if (expireAt > 0 && now > expireAt) {
                        // Key has expired; mark as tombstone
                        UnsafeAccess.putLong(slotAddr + OFFSET_HASH, TOMBSTONE_HASH);
                        size--;
                        tombstones++;
                        return false;
                    }

                    long valPtr = UnsafeAccess.getLong(slotAddr + OFFSET_VAL_PTR);
                    int valLen = UnsafeAccess.getInt(slotAddr + OFFSET_VAL_LEN);
                    result.set(valPtr, valLen);
                    return true;
                }
            }

            index = (index + 1) & mask;
        }

        return false;
    }

    public boolean delete(long keyAddr, int keyLen) {
        long hash = UnsafeAccess.hash64(keyAddr, keyLen);
        if (hash == EMPTY_HASH || hash == TOMBSTONE_HASH) {
            hash = 1L;
        }

        int index = (int) (hash & mask);

        for (int i = 0; i < capacity; i++) {
            long slotAddr = tableAddress + ((long) index) * SLOT_SIZE;
            long slotHash = UnsafeAccess.getLong(slotAddr + OFFSET_HASH);

            if (slotHash == EMPTY_HASH) {
                return false;
            }

            if (slotHash == hash) {
                int existingKeyLen = UnsafeAccess.getInt(slotAddr + OFFSET_KEY_LEN);
                long existingKeyPtr = UnsafeAccess.getLong(slotAddr + OFFSET_KEY_PTR);

                if (UnsafeAccess.memoryEquals(existingKeyPtr, existingKeyLen, keyAddr, keyLen)) {
                    UnsafeAccess.putLong(slotAddr + OFFSET_HASH, TOMBSTONE_HASH);
                    size--;
                    tombstones++;
                    return true;
                }
            }

            index = (index + 1) & mask;
        }

        return false;
    }

    public boolean exists(long keyAddr, int keyLen) {
        long hash = UnsafeAccess.hash64(keyAddr, keyLen);
        if (hash == EMPTY_HASH || hash == TOMBSTONE_HASH) {
            hash = 1L;
        }

        int index = (int) (hash & mask);
        long now = System.currentTimeMillis();

        for (int i = 0; i < capacity; i++) {
            long slotAddr = tableAddress + ((long) index) * SLOT_SIZE;
            long slotHash = UnsafeAccess.getLong(slotAddr + OFFSET_HASH);

            if (slotHash == EMPTY_HASH) {
                return false;
            }

            if (slotHash == hash) {
                int existingKeyLen = UnsafeAccess.getInt(slotAddr + OFFSET_KEY_LEN);
                long existingKeyPtr = UnsafeAccess.getLong(slotAddr + OFFSET_KEY_PTR);

                if (UnsafeAccess.memoryEquals(existingKeyPtr, existingKeyLen, keyAddr, keyLen)) {
                    long expireAt = UnsafeAccess.getLong(slotAddr + OFFSET_EXPIRE_AT);
                    if (expireAt > 0 && now > expireAt) {
                        UnsafeAccess.putLong(slotAddr + OFFSET_HASH, TOMBSTONE_HASH);
                        size--;
                        tombstones++;
                        return false;
                    }
                    return true;
                }
            }

            index = (index + 1) & mask;
        }

        return false;
    }

    private void resize() {
        int newCapacity = capacity << 1;
        int newMask = newCapacity - 1;
        long newTableBytes = (long) newCapacity * SLOT_SIZE;
        long newTableAddress = UnsafeAccess.allocateMemory(newTableBytes);
        UnsafeAccess.setMemory(newTableAddress, newTableBytes, (byte) 0);

        long now = System.currentTimeMillis();
        int rehashedCount = 0;

        for (int i = 0; i < capacity; i++) {
            long oldSlotAddr = tableAddress + ((long) i) * SLOT_SIZE;
            long hash = UnsafeAccess.getLong(oldSlotAddr + OFFSET_HASH);

            if (hash != EMPTY_HASH && hash != TOMBSTONE_HASH) {
                long expireAt = UnsafeAccess.getLong(oldSlotAddr + OFFSET_EXPIRE_AT);
                if (expireAt > 0 && now > expireAt) {
                    continue; // Skip expired keys during rehash
                }

                int newIndex = (int) (hash & newMask);
                while (UnsafeAccess.getLong(newTableAddress + ((long) newIndex) * SLOT_SIZE + OFFSET_HASH) != EMPTY_HASH) {
                    newIndex = (newIndex + 1) & newMask;
                }

                long newSlotAddr = newTableAddress + ((long) newIndex) * SLOT_SIZE;
                UnsafeAccess.copyMemory(oldSlotAddr, newSlotAddr, SLOT_SIZE);
                rehashedCount++;
            }
        }

        UnsafeAccess.freeMemory(tableAddress);
        this.tableAddress = newTableAddress;
        this.capacity = newCapacity;
        this.mask = newMask;
        this.threshold = (int) (newCapacity * LOAD_FACTOR);
        this.size = rehashedCount;
        this.tombstones = 0;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        long tableBytes = (long) capacity * SLOT_SIZE;
        UnsafeAccess.setMemory(tableAddress, tableBytes, (byte) 0);
        size = 0;
        tombstones = 0;
        arena.reset();
    }

    @Override
    public void close() {
        if (tableAddress != 0) {
            UnsafeAccess.freeMemory(tableAddress);
            tableAddress = 0;
        }
        arena.close();
        size = 0;
    }

    private static int nextPowerOfTwo(int val) {
        int highestOne = Integer.highestOneBit(val);
        return (highestOne == val) ? val : highestOne << 1;
    }
}
