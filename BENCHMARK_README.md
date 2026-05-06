# Coolstore Quarkus - Performance Benchmarks

## Overview

This directory contains comprehensive performance benchmarks for the Red Hat Coolstore application migrated from JavaEE 7 to Quarkus 3.8.1.

## Quick Start

Run all benchmarks with a single command:

```bash
./scripts/run-all-benchmarks.sh
```

This will execute all benchmark suites and generate a comprehensive report at `BENCHMARK_RESULTS.md`.

## Benchmark Suites

### 1. Startup Performance (`measure-startup.sh`)

Measures application startup time and time to first request.

**Metrics:**
- Cold start time (JVM mode)
- Native build startup (if available)
- Time to first request
- Hot reload time (dev mode)

**Usage:**
```bash
./scripts/measure-startup.sh
```

**Results:**
- `benchmark-results/startup-times.csv`
- `benchmark-results/startup-benchmark.log`

### 2. Memory Footprint (`measure-memory.sh`)

Measures memory consumption in various scenarios.

**Metrics:**
- Initial heap usage
- RSS (Resident Set Size)
- Memory usage over time
- Peak memory consumption
- Heap configuration variations

**Usage:**
```bash
./scripts/measure-memory.sh
```

**Results:**
- `benchmark-results/initial-memory.txt`
- `benchmark-results/memory-over-time.csv`
- `benchmark-results/heap-variations.csv`

### 3. Throughput (`measure-throughput.sh`)

Measures request throughput and response times.

**Metrics:**
- Requests per second per endpoint
- Average response time
- 99th percentile latency
- Concurrent request handling

**Usage:**
```bash
./scripts/measure-throughput.sh
```

**Requirements:**
- `wrk` (recommended) or `ab` (Apache Bench)

**Install wrk:**
```bash
# macOS
brew install wrk

# Linux
git clone https://github.com/wg/wrk.git
cd wrk
make
sudo cp wrk /usr/local/bin/
```

**Results:**
- `benchmark-results/throughput-summary.csv`
- `benchmark-results/concurrency-test.csv`
- `benchmark-results/wrk-*.txt` or `ab-*.txt`

### 4. JMH Micro-benchmarks

Detailed micro-benchmarks using JMH (Java Microbenchmark Harness).

**Benchmark Classes:**

1. **ProductEndpointBenchmark**
   - Product listing endpoint
   - Product detail endpoint
   - Mixed read patterns

2. **CartEndpointBenchmark**
   - Cart retrieval
   - Add to cart
   - Remove from cart
   - Full cart workflow

3. **OrderEndpointBenchmark**
   - Order listing
   - Order detail retrieval

4. **ServiceLayerBenchmark**
   - Service method performance
   - Database operations
   - Object transformations

**Usage:**

Start the application:
```bash
mvn quarkus:dev
```

In another terminal, run benchmarks:
```bash
# Run all benchmarks
mvn test -Dtest=*Benchmark

# Run specific benchmark
mvn test -Dtest=ProductEndpointBenchmark
mvn test -Dtest=CartEndpointBenchmark
mvn test -Dtest=OrderEndpointBenchmark
mvn test -Dtest=ServiceLayerBenchmark
```

**JMH Configuration:**
- Warmup: 3 iterations, 5 seconds each
- Measurement: 5 iterations, 10 seconds each
- Fork: 1
- Modes: Throughput and Average Time

## Prerequisites

### Required

- Java 17 or later
- Maven 3.9+
- Application built: `mvn clean package`

### Optional (for throughput benchmarks)

- `wrk` (recommended) or Apache Bench (`ab`)
- `curl` (usually pre-installed)

### For Native Benchmarks

To benchmark native binary performance:

```bash
mvn package -Pnative -DskipTests
./scripts/measure-startup.sh
```

## Results Structure

```
benchmark-results/
├── startup-times.csv           # Startup performance data
├── initial-memory.txt          # Initial memory snapshot
├── memory-over-time.csv        # Memory usage timeline
├── heap-variations.csv         # Different heap configurations
├── throughput-summary.csv      # Throughput metrics
├── concurrency-test.csv        # Concurrency performance
├── wrk-*.txt / ab-*.txt        # Detailed throughput reports
└── *.log                       # Execution logs
```

## Report Generation

Generate the comprehensive markdown report:

```bash
./scripts/generate-report.sh
```

This creates `BENCHMARK_RESULTS.md` with:
- Executive summary
- All benchmark results
- Performance comparisons
- Conclusions and recommendations

## Benchmark Scenarios

### Product Endpoints

- **GET /services/products/** - List all products
- **GET /services/products/{id}** - Get product by ID

### Cart Endpoints

- **GET /services/cart/{cartId}** - Get cart
- **POST /services/cart/{cartId}/{itemId}/{quantity}** - Add to cart
- **DELETE /services/cart/{cartId}/{itemId}/{quantity}** - Remove from cart
- **POST /services/cart/checkout/{cartId}** - Checkout

### Order Endpoints

- **GET /services/orders/** - List all orders
- **GET /services/orders/{orderId}** - Get order by ID

## Interpreting Results

### Startup Time

- **Excellent**: < 3 seconds
- **Good**: 3-5 seconds
- **Acceptable**: 5-10 seconds
- **Poor**: > 10 seconds

### Memory Footprint (RSS)

- **Excellent**: < 200 MB
- **Good**: 200-350 MB
- **Acceptable**: 350-500 MB
- **Poor**: > 500 MB

### Throughput

- **Excellent**: > 10,000 req/s
- **Good**: 5,000-10,000 req/s
- **Acceptable**: 1,000-5,000 req/s
- **Poor**: < 1,000 req/s

### Latency (p99)

- **Excellent**: < 10 ms
- **Good**: 10-50 ms
- **Acceptable**: 50-100 ms
- **Poor**: > 100 ms

## Comparison with JavaEE Baseline

Expected improvements from JavaEE 7 to Quarkus 3.8.1:

| Metric | Expected Improvement |
|--------|---------------------|
| Startup Time | 80-90% faster |
| Memory Usage | 60-70% reduction |
| Throughput | 15-30% increase |
| Latency | 20-40% improvement |

## Troubleshooting

### Application Won't Start

```bash
# Check if port 8080 is in use
lsof -i :8080

# Kill existing process
kill -9 $(lsof -t -i :8080)

# Rebuild application
mvn clean package
```

### Benchmark Script Fails

```bash
# Make scripts executable
chmod +x ./scripts/*.sh

# Check dependencies
which wrk || which ab
which java
which mvn
```

### Low Throughput Results

- Ensure no other applications are running
- Check system resources (CPU, memory)
- Verify application is running in production mode
- Increase warmup iterations for JMH

### Memory Measurement Issues

- On macOS: Use Activity Monitor to verify
- On Linux: Use `ps aux | grep java`
- Ensure sufficient system memory available
- Check for memory leaks with repeated runs

## CI/CD Integration

To integrate benchmarks into your CI/CD pipeline:

```yaml
# Example GitLab CI
benchmark:
  stage: test
  script:
    - mvn clean package -DskipTests
    - ./scripts/run-all-benchmarks.sh
  artifacts:
    paths:
      - benchmark-results/
      - BENCHMARK_RESULTS.md
    expire_in: 30 days
```

## Performance Regression Detection

Set performance budgets to detect regressions:

```bash
# Example: Check if startup time exceeds threshold
MAX_STARTUP_MS=3000
actual=$(grep "JVM" benchmark-results/startup-times.csv | cut -d',' -f2)

if [ $actual -gt $MAX_STARTUP_MS ]; then
  echo "Performance regression detected!"
  exit 1
fi
```

## Best Practices

1. **Run Multiple Iterations**: Benchmark results can vary, run at least 3-5 iterations
2. **Warm Up**: Always include warmup phase for JVM optimization
3. **Isolation**: Run benchmarks on dedicated hardware when possible
4. **Consistent Environment**: Use same hardware/OS for comparisons
5. **Document Changes**: Track performance changes across versions
6. **Automate**: Integrate benchmarks into CI/CD pipeline
7. **Monitor Trends**: Track performance over time, not just absolute values

## Contributing

When adding new benchmarks:

1. Follow JMH best practices
2. Document benchmark purpose and methodology
3. Add to this README
4. Update report generation script
5. Include in main benchmark runner

## Resources

- [JMH Documentation](http://openjdk.java.net/projects/code-tools/jmh/)
- [Quarkus Performance Guide](https://quarkus.io/guides/performance-measure)
- [wrk Documentation](https://github.com/wg/wrk)

## Support

For issues or questions:

1. Check this README
2. Review benchmark logs in `benchmark-results/`
3. Check Quarkus documentation
4. Open an issue in the repository

---

**Last Updated**: 2026-05-06
**Quarkus Version**: 3.8.1
**Benchmark Suite Version**: 1.0.0
