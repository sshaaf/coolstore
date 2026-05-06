# Red Hat Coolstore - Performance Benchmarks

## Executive Summary

Comprehensive performance benchmark suite for validating the Quarkus 3.8.1 migration.

**Status**: ✅ Complete and Ready for Execution

---

## Quick Start

```bash
# Run all benchmarks
./scripts/run-all-benchmarks.sh

# View results
cat BENCHMARK_RESULTS.md
```

---

## What's Included

### 1. JMH Micro-benchmarks
- Product endpoint benchmarks
- Shopping cart benchmarks
- Order endpoint benchmarks
- Service layer benchmarks

**Run**: `mvn quarkus:dev` then `mvn test -Dtest=*Benchmark`

### 2. Startup Performance
- Cold start time
- Time to first request
- Native binary support

**Run**: `./scripts/measure-startup.sh`

### 3. Memory Footprint
- RSS measurement
- Heap monitoring
- Memory over time
- Configuration comparisons

**Run**: `./scripts/measure-memory.sh`

### 4. Throughput & Concurrency
- Requests per second
- Latency percentiles
- Concurrent load testing
- Multiple endpoint coverage

**Run**: `./scripts/measure-throughput.sh`

---

## Expected Results

### Performance Improvements (vs JavaEE 7)
- Startup: 83% faster (< 3s vs ~18s)
- Memory: 67% reduction (< 250 MB vs ~600 MB)
- Throughput: 43% increase (> 5,000 vs ~3,500 req/s)
- Latency: 41% improvement (< 50ms vs ~85ms p99)

---

## Documentation

- **Quick Start**: [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md)
- **Complete Guide**: [BENCHMARK_README.md](BENCHMARK_README.md)
- **Navigation**: [BENCHMARK_INDEX.md](BENCHMARK_INDEX.md)
- **Deliverables**: [BENCHMARK_DELIVERABLES.md](BENCHMARK_DELIVERABLES.md)
- **Results**: [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md)

---

## Files Created

**Total**: 18 files (~100KB documentation, ~3,500 lines of code)

- 6 Java benchmark classes
- 5 Shell scripts
- 6 Documentation files
- 1 Configuration file

---

## Prerequisites

- Java 17+
- Maven 3.9+
- wrk or Apache Bench (for throughput)

---

## Next Steps

1. Run benchmarks: `./scripts/run-all-benchmarks.sh`
2. Review results: `cat BENCHMARK_RESULTS.md`
3. Validate targets met
4. Integrate into CI/CD

---

**Created**: 2026-05-06
**Framework**: Quarkus 3.8.1
**Agent**: benchmark-builder-agent
