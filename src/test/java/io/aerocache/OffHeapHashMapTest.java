package io.aerocache;

import io.aerocache.collection.EntryResult;
import io.aerocache.collection.OffHeapHashMap;
import io.aerocache.memory.OffHeapArena;
import io.aerocache.memory.UnsafeAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapHashMapTest {

    private OffHeapArena arena;
    private OffHeapHashMap map;
    private final EntryResult result = new EntryResult();

    @BeforeEach
    void setUp() {
        arena = new OffHeapArena(4 * 1024 * 1024); // 4 MB slab
        map = new OffHeapHashMap(64, arena);
    }

    @AfterEach
    void tearDown() {
        map.close();
    }

    private long allocateString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        long addr = UnsafeAccess.allocateMemory(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            UnsafeAccess.putByte(addr + i, bytes[i]);
        }
        return addr;
    }

    private String readString(long addr, int len) {
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[i] = UnsafeAccess.getByte(addr + i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void testBasicPutAndGet() {
        long keyAddr = allocateString("hft_symbol");
        int keyLen = 10;
        long valAddr = allocateString("AAPL_NASDAQ");
        int valLen = 11;

        try {
            boolean isNew = map.put(keyAddr, keyLen, valAddr, valLen, 0);
            assertTrue(isNew);
            assertEquals(1, map.size());

            boolean found = map.get(keyAddr, keyLen, result);
            assertTrue(found);
            assertEquals(valLen, result.valLength);
            assertEquals("AAPL_NASDAQ", readString(result.valAddress, result.valLength));
        } finally {
            UnsafeAccess.freeMemory(keyAddr);
            UnsafeAccess.freeMemory(valAddr);
        }
    }

    @Test
    void testUpdateExistingKey() {
        long keyAddr = allocateString("price");
        int keyLen = 5;
        long val1 = allocateString("180.25");
        long val2 = allocateString("181.50");

        try {
            assertTrue(map.put(keyAddr, keyLen, val1, 6, 0));
            assertEquals(1, map.size());

            assertFalse(map.put(keyAddr, keyLen, val2, 6, 0));
            assertEquals(1, map.size());

            assertTrue(map.get(keyAddr, keyLen, result));
            assertEquals("181.50", readString(result.valAddress, result.valLength));
        } finally {
            UnsafeAccess.freeMemory(keyAddr);
            UnsafeAccess.freeMemory(val1);
            UnsafeAccess.freeMemory(val2);
        }
    }

    @Test
    void testDeleteAndExists() {
        long keyAddr = allocateString("order_id_99");
        int keyLen = 11;
        long valAddr = allocateString("FILLED");
        int valLen = 6;

        try {
            assertFalse(map.exists(keyAddr, keyLen));
            assertFalse(map.delete(keyAddr, keyLen));

            map.put(keyAddr, keyLen, valAddr, valLen, 0);
            assertTrue(map.exists(keyAddr, keyLen));
            assertEquals(1, map.size());

            assertTrue(map.delete(keyAddr, keyLen));
            assertFalse(map.exists(keyAddr, keyLen));
            assertEquals(0, map.size());
            assertFalse(map.get(keyAddr, keyLen, result));
        } finally {
            UnsafeAccess.freeMemory(keyAddr);
            UnsafeAccess.freeMemory(valAddr);
        }
    }

    @Test
    void testKeyTtlExpiration() throws InterruptedException {
        long keyAddr = allocateString("ephemeral_token");
        int keyLen = 15;
        long valAddr = allocateString("secret_123");
        int valLen = 10;

        try {
            map.put(keyAddr, keyLen, valAddr, valLen, 60); // 60ms TTL
            assertTrue(map.exists(keyAddr, keyLen));
            assertTrue(map.get(keyAddr, keyLen, result));

            Thread.sleep(80); // Wait for expiration

            assertFalse(map.get(keyAddr, keyLen, result));
            assertFalse(map.exists(keyAddr, keyLen));
            assertEquals(0, map.size());
        } finally {
            UnsafeAccess.freeMemory(keyAddr);
            UnsafeAccess.freeMemory(valAddr);
        }
    }

    @Test
    void testHeavyLoadFactorAndResizing() {
        int count = 2000;
        long[] keys = new long[count];
        long[] vals = new long[count];
        int[] lens = new int[count];

        for (int i = 0; i < count; i++) {
            String k = "key_prefix_" + i;
            String v = "val_payload_" + i;
            keys[i] = allocateString(k);
            vals[i] = allocateString(v);
            lens[i] = k.length();

            map.put(keys[i], lens[i], vals[i], v.length(), 0);
        }

        assertEquals(count, map.size());
        assertTrue(map.capacity() >= count);

        // Verify all keys remain intact across dynamic rehashes
        for (int i = 0; i < count; i++) {
            String expected = "val_payload_" + i;
            assertTrue(map.get(keys[i], lens[i], result), "Failed to find " + i);
            assertEquals(expected, readString(result.valAddress, result.valLength));
        }

        // Cleanup
        for (int i = 0; i < count; i++) {
            UnsafeAccess.freeMemory(keys[i]);
            UnsafeAccess.freeMemory(vals[i]);
        }
    }

    @Test
    void testClear() {
        long keyAddr = allocateString("k");
        long valAddr = allocateString("v");
        try {
            map.put(keyAddr, 1, valAddr, 1, 0);
            assertEquals(1, map.size());
            map.clear();
            assertEquals(0, map.size());
            assertFalse(map.exists(keyAddr, 1));
        } finally {
            UnsafeAccess.freeMemory(keyAddr);
            UnsafeAccess.freeMemory(valAddr);
        }
    }
}
