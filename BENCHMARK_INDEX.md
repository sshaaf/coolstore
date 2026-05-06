# Benchmark Suite - Navigation Index

Quick reference to all benchmark resources for the Red Hat Coolstore Quarkus migration.

---

## 🚀 Quick Start

**New to benchmarks? Start here:**
1. Read: [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md)
2. Run: `./scripts/run-all-benchmarks.sh`
3. View: [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md)

---

## 📚 Documentation

| Document | Purpose | When to Read |
|----------|---------|--------------|
| [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md) | Quick commands and setup | First time user |
| [BENCHMARK_README.md](BENCHMARK_README.md) | Comprehensive guide | Need detailed information |
| [BENCHMARK_DELIVERABLES.md](BENCHMARK_DELIVERABLES.md) | Complete inventory | Understanding what's included |
| [BENCHMARK_COMPLETION_SUMMARY.md](BENCHMARK_COMPLETION_SUMMARY.md) | Implementation report | Project status |
| [BENCHMARK_INDEX.md](BENCHMARK_INDEX.md) | This file - Navigation | Finding resources |

---

## 🔧 Configuration

| File | Purpose |
|------|---------|
| [benchmark-config.yaml](benchmark-config.yaml) | Central configuration for all benchmarks |
| [pom.xml](pom.xml) | Maven dependencies (JMH added) |

---

## 💻 Source Code

### JMH Benchmarks
Located in: `src/test/java/com/redhat/coolstore/benchmarks/`

| Class | Tests |
|-------|-------|
| ProductEndpointBenchmark | Product listing, detail, mixed patterns |
| CartEndpointBenchmark | Cart operations, full workflow |
| OrderEndpointBenchmark | Order listing, detail |
| ServiceLayerBenchmark | Service methods, DB operations |
| BenchmarkRunner | JMH execution runner |
| BenchmarkVerificationTest | Infrastructure validation |

### Shell Scripts
Located in: `scripts/`

| Script | Purpose |
|--------|---------|
| run-all-benchmarks.sh | Execute all benchmark suites |
| measure-startup.sh | Startup performance |
| measure-memory.sh | Memory footprint |
| measure-throughput.sh | Throughput and concurrency |
| generate-report.sh | Create markdown report |

---

## 📊 Results

| Output | Location | Description |
|--------|----------|-------------|
| Main Report | BENCHMARK_RESULTS.md | Comprehensive results |
| Startup Data | benchmark-results/startup-times.csv | Cold start metrics |
| Memory Data | benchmark-results/memory-over-time.csv | Memory tracking |
| Throughput Data | benchmark-results/throughput-summary.csv | RPS and latency |
| Concurrency Data | benchmark-results/concurrency-test.csv | Load testing |
| JMH Results | benchmark-results/jmh-results.json | Micro-benchmark data |
| Logs | benchmark-results/*.log | Execution logs |

---

## 🎯 Use Cases

### I want to...

#### Run all benchmarks
```bash
./scripts/run-all-benchmarks.sh
```
See: [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md)

#### Run specific benchmark
```bash
./scripts/measure-startup.sh
./scripts/measure-memory.sh
./scripts/measure-throughput.sh
```
See: [BENCHMARK_README.md](BENCHMARK_README.md#benchmark-suites)

#### Run JMH micro-benchmarks
```bash
mvn quarkus:dev &
mvn test -Dtest=ProductEndpointBenchmark
```
See: [BENCHMARK_README.md](BENCHMARK_README.md#4-jmh-micro-benchmarks)

#### Understand results
Read: [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md)
See: [BENCHMARK_README.md](BENCHMARK_README.md#interpreting-results)

#### Change configuration
Edit: [benchmark-config.yaml](benchmark-config.yaml)
See: [BENCHMARK_README.md](BENCHMARK_README.md#configuration)

#### Troubleshoot issues
See: [BENCHMARK_README.md](BENCHMARK_README.md#troubleshooting)

#### Integrate with CI/CD
See: [BENCHMARK_README.md](BENCHMARK_README.md#cicd-integration)

#### Add new benchmarks
See: [BENCHMARK_README.md](BENCHMARK_README.md#contributing)

---

## 📈 Performance Targets

From [benchmark-config.yaml](benchmark-config.yaml):

| Metric | JavaEE 7 | Quarkus 3.8.1 | Improvement |
|--------|----------|---------------|-------------|
| Startup Time | 18s | < 3s | 83% |
| Memory (RSS) | 600 MB | < 250 MB | 67% |
| Throughput | 3,500 req/s | > 5,000 req/s | 43% |
| Latency (p99) | 85 ms | < 50 ms | 41% |

---

## 🔍 Quick Reference

### Dependencies
- Java 17+
- Maven 3.9+
- curl (usually pre-installed)
- wrk or Apache Bench (for throughput tests)

### Install wrk
```bash
# macOS
brew install wrk

# Linux
sudo apt-get install apache2-utils  # for ab
```

### Verify Setup
```bash
mvn test -Dtest=BenchmarkVerificationTest
```

### Make Scripts Executable
```bash
chmod +x ./scripts/*.sh
```

---

## 🎓 Learning Path

1. **Beginner**: Start with [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md)
2. **Intermediate**: Read [BENCHMARK_README.md](BENCHMARK_README.md)
3. **Advanced**: Review [benchmark-config.yaml](benchmark-config.yaml) and source code
4. **Expert**: Extend benchmarks and integrate with CI/CD

---

## 📞 Getting Help

1. Check this index
2. Read relevant documentation
3. Review logs in `benchmark-results/*.log`
4. See troubleshooting in [BENCHMARK_README.md](BENCHMARK_README.md#troubleshooting)

---

## ✅ Checklist for First Run

- [ ] Java 17+ installed
- [ ] Maven 3.9+ installed
- [ ] Application built: `mvn clean package`
- [ ] Scripts executable: `chmod +x ./scripts/*.sh`
- [ ] Port 8080 available
- [ ] wrk or ab installed (for throughput tests)
- [ ] Read [BENCHMARK_QUICKSTART.md](BENCHMARK_QUICKSTART.md)

---

## 🏆 Success Criteria

After running benchmarks, verify:
- [ ] All scripts executed without errors
- [ ] BENCHMARK_RESULTS.md generated
- [ ] Results meet quality gates
- [ ] No performance regressions
- [ ] Startup < 5 seconds
- [ ] Memory < 350 MB
- [ ] Throughput > 2,000 req/s

---

**Last Updated**: 2026-05-06
**Version**: 1.0.0
**Status**: Complete and Ready
