package io.aerocache;

import io.aerocache.collection.EntryResult;
import io.aerocache.collection.OffHeapHashMap;
import io.aerocache.memory.OffHeapArena;
import io.aerocache.memory.UnsafeAccess;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryAllocationBenchmark {

    @Test
    void benchmarkLatencyAndZeroGc() {
        int operations = 200_000;
        OffHeapArena arena = new OffHeapArena(16 * 1024 * 1024);
        OffHeapHashMap map = new OffHeapHashMap(operations * 2, arena);
        EntryResult result = new EntryResult();

        // Pre-allocate raw key/val off-heap buffers
        byte[] keyBytes = "order:HFT:10948572".getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = "BUY 500 AAPL @ 182.45 LIMIT GTC".getBytes(StandardCharsets.UTF_8);

        long keyAddr = UnsafeAccess.allocateMemory(keyBytes.length);
        long valAddr = UnsafeAccess.allocateMemory(valBytes.length);

        for (int i = 0; i < keyBytes.length; i++) UnsafeAccess.putByte(keyAddr + i, keyBytes[i]);
        for (int i = 0; i < valBytes.length; i++) UnsafeAccess.putByte(valAddr + i, valBytes[i]);

        // 1. Warm-up JIT compiler
        for (int i = 0; i < 20_000; i++) {
            map.put(keyAddr, keyBytes.length, valAddr, valBytes.length, 0);
            map.get(keyAddr, keyBytes.length, result);
        }

        // 2. Measure GC metrics before benchmark
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCountBefore = 0;
        long gcTimeBefore = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            gcCountBefore += gc.getCollectionCount();
            gcTimeBefore += gc.getCollectionTime();
        }

        // 3. Timed execution & Latency Percentiles
        long[] latenciesNanos = new long[operations];
        long startBench = System.nanoTime();

        for (int i = 0; i < operations; i++) {
            long t0 = System.nanoTime();
            map.get(keyAddr, keyBytes.length, result);
            latenciesNanos[i] = System.nanoTime() - t0;
        }

        long totalNanos = System.nanoTime() - startBench;

        // 4. Measure GC metrics after benchmark
        long gcCountAfter = 0;
        long gcTimeAfter = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            gcCountAfter += gc.getCollectionCount();
            gcTimeAfter += gc.getCollectionTime();
        }

        long gcCountDelta = gcCountAfter - gcCountBefore;
        long gcTimeDelta = gcTimeAfter - gcTimeBefore;

        // 5. Calculate percentiles
        Arrays.sort(latenciesNanos);
        double p50Micros = latenciesNanos[(int) (operations * 0.50)] / 1000.0;
        double p90Micros = latenciesNanos[(int) (operations * 0.90)] / 1000.0;
        double p99Micros = latenciesNanos[(int) (operations * 0.99)] / 1000.0;
        double p999Micros = latenciesNanos[(int) (operations * 0.999)] / 1000.0;
        double throughputOpsPerSec = ((double) operations) / (totalNanos / 1_000_000_000.0);

        System.out.println("=================================================================");
        System.out.println("  AEROCACHE ZERO-ALLOCATION BENCHMARK RESULTS (" + operations + " ops)");
        System.out.println("=================================================================");
        System.out.printf("  Throughput (Read)   : %,.0f ops/sec\n", throughputOpsPerSec);
        System.out.printf("  p50 (Median) Latency: %.2f µs\n", p50Micros);
        System.out.printf("  p90 Latency         : %.2f µs\n", p90Micros);
        System.out.printf("  p99 Latency         : %.2f µs\n", p99Micros);
        System.out.printf("  p99.9 Latency       : %.2f µs\n", p999Micros);
        System.out.printf("  Total GC Events     : %d pauses\n", gcCountDelta);
        System.out.printf("  Total GC Time       : %d ms\n", gcTimeDelta);
        System.out.println("=================================================================");

        // Verify strictly zero GC overhead during the entire 200,000-operation run
        assertEquals(0, gcCountDelta, "Expected 0 GC pauses during steady-state off-heap caching!");
        assertEquals(0, gcTimeDelta, "Expected 0 ms GC pause time during steady-state off-heap caching!");
        assertTrue(p99Micros < 100.0, "Expected p99 latency under 100 µs");

        // Cleanup
        UnsafeAccess.freeMemory(keyAddr);
        UnsafeAccess.freeMemory(valAddr);
        map.close();
    }
}
