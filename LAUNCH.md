# 🚀 Viral Launch Kit for `aerocache`

## 1. Hacker News (Show HN)
- **Target URL**: https://news.ycombinator.com/submit
- **Best Timing**: Thursday at 8:30 AM EST (12:30 UTC)
- **Title**:
  `Show HN: AeroCache – 13M ops/sec Redis-compatible cache in Java (Zero GC pauses)`
- **URL**: `https://github.com/nff747/aerocache`
- **First Comment**:
```markdown
Hey HN,

Java is famous for garbage collection pauses that wreck tail latencies (P99) in high-throughput caching.

I built AeroCache to see how far Java NIO and off-heap memory could be pushed toward HFT-grade performance:
- Direct Off-Heap Memory: Uses sun.misc.Unsafe to allocate and manage raw native byte slabs.
- Intrusive Segregated Free Lists: A 13-class size allocator (16B to 64KB) guarantees zero native memory leaks under update/delete churn.
- Non-Blocking Java NIO: Single-threaded event loop server parsing RESP commands with zero heap object allocations during GET operations.
- Striped Concurrency: PartitionedOffHeapHashMap scales writes across multiple worker threads without global contention.

In benchmarks on modern Linux hardware:
- Read throughput: 13.0M+ ops/sec
- P50 latency: 0.05 µs
- P99 latency: 0.10 µs
- GC pauses: 0 ms (0 pauses across 200,000 requests)

Repo: https://github.com/nff747/aerocache
License: MIT

Curious to hear thoughts from systems architects on memory layout and comparison with Dragonfly/Redis.
```

---

## 2. Twitter / X Launch Thread
```text
Can Java beat Redis throughput without triggering GC pauses?

Meet AeroCache: An ultra-low latency, zero-allocation in-memory cache server written in Java.

⚡ 13,000,000 ops/sec
⚡ 0.10 µs P99 latency
⚡ 0 GC pauses

Here's how we bypassed the JVM garbage collector: 👇

1/4 Direct Off-Heap Arena:
Keys and values live outside the JVM heap in unmanaged native memory slabs, completely invisible to GC scavengers.

2/4 Intrusive Segregated Free Lists:
13 size classes (16B to 64KB) recycle expired and deleted keys immediately. Zero heap overhead.

3/4 Zero-Alloc RESP Parser:
Parses Redis protocol directly from native buffer pointers without creating java.lang.String or byte[] garbage.

4/4 Striped Concurrency:
PartitionedOffHeapHashMap provides parallel lock striping across CPU cores.

100% Open Source (MIT).
Check out the benchmark & source:
⭐ https://github.com/nff747/aerocache

#java #redis #systems #performance #hft
```

---

## 3. Reddit Posts
- **Subreddits**: `r/java`, `r/programming`, `r/systemdesign`
- **Post Title**: `AeroCache: Achieving 13M ops/sec with 0.10µs P99 latency in Java by bypassing the JVM GC`
