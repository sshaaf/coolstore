# Benchmark Quick Start Guide

## Run All Benchmarks (Recommended)

```bash
./scripts/run-all-benchmarks.sh
```

This will:
1. Run startup performance tests
2. Run memory footprint tests
3. Run throughput tests
4. Generate comprehensive report

Results: `BENCHMARK_RESULTS.md`

## Run Individual Benchmarks

### 1. Startup Performance

```bash
./scripts/measure-startup.sh
```

Measures: Cold start time, time to first request

### 2. Memory Footprint

```bash
./scripts/measure-memory.sh
```

Measures: RSS, heap usage, memory over time

### 3. Throughput

```bash
./scripts/measure-throughput.sh
```

Measures: Requests/sec, latency, concurrency

Requires: `wrk` or `ab` (Apache Bench)

Install wrk:
```bash
# macOS
brew install wrk

# Linux
sudo apt-get install apache2-utils  # for ab
```

### 4. JMH Micro-benchmarks

Start application:
```bash
mvn quarkus:dev
```

Run benchmarks (in another terminal):
```bash
mvn test -Dtest=ProductEndpointBenchmark
mvn test -Dtest=CartEndpointBenchmark
mvn test -Dtest=OrderEndpointBenchmark
mvn test -Dtest=ServiceLayerBenchmark
```

## Prerequisites

- Java 17+
- Maven 3.9+
- Built application: `mvn clean package`

## View Results

- **Comprehensive Report**: `BENCHMARK_RESULTS.md`
- **Raw Data**: `benchmark-results/` directory
- **Detailed README**: `BENCHMARK_README.md`

## Quick Validation

After migration, verify performance improvements:

```bash
# Run all benchmarks
./scripts/run-all-benchmarks.sh

# Check key metrics in report
cat BENCHMARK_RESULTS.md | grep -A 5 "Comparison with JavaEE"
```

## Expected Results (Quarkus 3.8.1)

- Startup: < 3 seconds
- Memory (RSS): 150-250 MB
- Throughput: > 5,000 req/s
- Latency (p99): < 50 ms

## Troubleshooting

**Application won't start:**
```bash
lsof -i :8080  # Check port
kill -9 $(lsof -t -i :8080)  # Kill process
mvn clean package  # Rebuild
```

**Scripts not executable:**
```bash
chmod +x ./scripts/*.sh
```

For more details, see `BENCHMARK_README.md`
