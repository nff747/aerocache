package io.aerocache;

import io.aerocache.collection.EntryResult;
import io.aerocache.collection.PartitionedOffHeapHashMap;
import io.aerocache.memory.UnsafeAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class PartitionedOffHeapHashMapTest {

    private PartitionedOffHeapHashMap partitionedMap;

    @BeforeEach
    void setUp() {
        // 4 partitions, 64 initial capacity per partition, 2MB slab per partition
        partitionedMap = new PartitionedOffHeapHashMap(4, 64, 2 * 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        partitionedMap.close();
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
    void testBasicPartitionedOperations() {
        EntryResult result = new EntryResult();
        long k1 = allocateString("key_alpha");
        long v1 = allocateString("val_alpha");

        try {
            assertTrue(partitionedMap.put(k1, 9, v1, 9, 0));
            assertEquals(1, partitionedMap.size());
            assertTrue(partitionedMap.exists(k1, 9));

            assertTrue(partitionedMap.get(k1, 9, result));
            assertEquals("val_alpha", readString(result.valAddress, result.valLength));

            assertTrue(partitionedMap.delete(k1, 9));
            assertEquals(0, partitionedMap.size());
            assertFalse(partitionedMap.exists(k1, 9));
        } finally {
            UnsafeAccess.freeMemory(k1);
            UnsafeAccess.freeMemory(v1);
        }
    }

    @Test
    void testConcurrentWritesAcrossPartitions() throws InterruptedException {
        int threadCount = 4;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String kStr = "th_" + threadId + "_k_" + i;
                        String vStr = "val_" + i;
                        byte[] kBytes = kStr.getBytes(StandardCharsets.UTF_8);
                        byte[] vBytes = vStr.getBytes(StandardCharsets.UTF_8);

                        long kAddr = UnsafeAccess.allocateMemory(kBytes.length);
                        long vAddr = UnsafeAccess.allocateMemory(vBytes.length);
                        for (int b = 0; b < kBytes.length; b++) UnsafeAccess.putByte(kAddr + b, kBytes[b]);
                        for (int b = 0; b < vBytes.length; b++) UnsafeAccess.putByte(vAddr + b, vBytes[b]);

                        partitionedMap.put(kAddr, kBytes.length, vAddr, vBytes.length, 0);

                        UnsafeAccess.freeMemory(kAddr);
                        UnsafeAccess.freeMemory(vAddr);
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount * operationsPerThread, successCount.get());
        assertEquals(threadCount * operationsPerThread, partitionedMap.size());
    }
}
