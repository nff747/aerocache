package io.aerocache.memory;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Low-level hardware memory accessor utilizing sun.misc.Unsafe.
 * Provides microsecond-latency off-heap memory allocation and word-level native operations.
 */
public final class UnsafeAccess {

    public static final Unsafe UNSAFE;

    private static final long BUFFER_ADDRESS_OFFSET;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);

            Field addrField = java.nio.Buffer.class.getDeclaredField("address");
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(addrField);
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Failed to acquire sun.misc.Unsafe or Buffer.address: " + e.getMessage());
        }
    }

    private UnsafeAccess() {}

    public static long getDirectBufferAddress(java.nio.Buffer buffer) {
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }

    public static long allocateMemory(long bytes) {
        return UNSAFE.allocateMemory(bytes);
    }

    public static void freeMemory(long address) {
        if (address != 0) {
            UNSAFE.freeMemory(address);
        }
    }

    public static void setMemory(long address, long bytes, byte value) {
        UNSAFE.setMemory(address, bytes, value);
    }

    public static void copyMemory(long srcAddress, long destAddress, long bytes) {
        UNSAFE.copyMemory(srcAddress, destAddress, bytes);
    }

    public static long getLong(long address) {
        return UNSAFE.getLong(address);
    }

    public static void putLong(long address, long value) {
        UNSAFE.putLong(address, value);
    }

    public static int getInt(long address) {
        return UNSAFE.getInt(address);
    }

    public static void putInt(long address, int value) {
        UNSAFE.putInt(address, value);
    }

    public static byte getByte(long address) {
        return UNSAFE.getByte(address);
    }

    public static void putByte(long address, byte value) {
        UNSAFE.putByte(address, value);
    }

    /**
     * Compares two off-heap memory segments for byte-for-byte equality.
     * Uses 8-byte word comparison to minimize memory bus transactions.
     */
    public static boolean memoryEquals(long addr1, int len1, long addr2, int len2) {
        if (len1 != len2) {
            return false;
        }
        if (addr1 == addr2) {
            return true;
        }

        int words = len1 >>> 3;
        for (int i = 0; i < words; i++) {
            long offset = ((long) i) << 3;
            if (UNSAFE.getLong(addr1 + offset) != UNSAFE.getLong(addr2 + offset)) {
                return false;
            }
        }

        int remainder = len1 & 7;
        long offset = ((long) words) << 3;
        for (int i = 0; i < remainder; i++) {
            if (UNSAFE.getByte(addr1 + offset + i) != UNSAFE.getByte(addr2 + offset + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 64-bit MurmurHash3 variant operating directly on raw native memory pointers.
     * Zero object allocation on heap.
     */
    public static long hash64(long address, int length) {
        long h = 0x9E3779B97F4A7C15L ^ ((long) length * 0xC6A4A7935BD1E995L);
        int words = length >>> 3;

        for (int i = 0; i < words; i++) {
            long k = UNSAFE.getLong(address + (((long) i) << 3));
            k *= 0xC6A4A7935BD1E995L;
            k ^= k >>> 47;
            k *= 0xC6A4A7935BD1E995L;

            h ^= k;
            h *= 0xC6A4A7935BD1E995L;
        }

        int remainder = length & 7;
        if (remainder > 0) {
            long offset = ((long) words) << 3;
            long k = 0;
            for (int i = remainder - 1; i >= 0; i--) {
                k = (k << 8) | (((long) UNSAFE.getByte(address + offset + i)) & 0xFFL);
            }
            k *= 0xC6A4A7935BD1E995L;
            k ^= k >>> 47;
            k *= 0xC6A4A7935BD1E995L;

            h ^= k;
            h *= 0xC6A4A7935BD1E995L;
        }

        h ^= h >>> 47;
        h *= 0xC6A4A7935BD1E995L;
        h ^= h >>> 47;

        // Ensure hash is never 0 so 0 can indicate an empty slot
        return (h == 0) ? 1L : h;
    }
}
