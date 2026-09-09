# Contributing to AeroCache

Thank you for contributing to **AeroCache**! We welcome bug fixes, performance optimizations, protocol extensions, and benchmark improvements.

## Prerequisites & Setup

AeroCache requires:
- Java Development Kit (JDK) 21 or newer.
- Apache Maven 3.9+.

```bash
mvn clean test
```

### Running the Micro-Benchmark

To evaluate off-heap memory allocation throughput and latency percentiles:

```bash
mvn test -Dtest=MemoryAllocationBenchmark
```

## Contribution Workflow

1. **Fork the repository** on GitHub.
2. **Create a topic branch**:
   ```bash
   git checkout -b feat/your-feature
   ```
3. **Write zero-allocation code**:
   - Ensure hot paths avoid any JVM heap allocation (`new` object creations during query processing).
   - Verify that all off-heap pointers allocated through `OffHeapArena` are freed upon key expiration or deletion.
4. **Run tests**:
   ```bash
   mvn test
   ```
5. **Open a Pull Request**.

## License

By contributing to AeroCache, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).
