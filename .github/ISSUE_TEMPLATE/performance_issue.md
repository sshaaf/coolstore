---
name: Performance Issue
about: Report a performance problem or regression
title: '[PERF] '
labels: performance
assignees: ''
---

## Performance Issue Description

<!-- Describe the performance issue -->

## Metric Affected

- [ ] Startup time
- [ ] Response time
- [ ] Throughput
- [ ] Memory usage
- [ ] CPU usage
- [ ] Database query performance
- [ ] Other: ___________

## Measurements

### Current Performance

<!-- Provide current measurements -->

- **Metric**: <!-- e.g., Response time -->
- **Current Value**: <!-- e.g., 500ms -->
- **Expected Value**: <!-- e.g., <200ms -->

### Benchmark Results

<!-- If you ran benchmarks, paste results here -->

```
# Paste benchmark results
```

## Environment

- **OS**: <!-- e.g., Ubuntu 22.04 -->
- **Java Version**: <!-- e.g., 17.0.8 -->
- **Quarkus Version**: <!-- e.g., 3.8.1 -->
- **Hardware**: <!-- e.g., 4 CPU, 8GB RAM -->
- **Deployment**: <!-- e.g., JVM mode, Native mode, Container -->

## Reproduction Steps

1.
2.
3.

## Profiling Data

<!-- If you have profiling data, include it here -->

### CPU Profile

<!-- Attach or link to CPU profile -->

### Memory Profile

<!-- Attach or link to memory profile -->

## Suspected Cause

<!-- If you have ideas about what might be causing this -->

## Comparison

<!-- Compare with previous version or baseline if available -->

| Metric | Baseline | Current | Change |
|--------|----------|---------|--------|
| Startup time | | | |
| Response time | | | |
| Throughput | | | |

## Impact

- [ ] Critical - System unusable
- [ ] High - Significant degradation
- [ ] Medium - Noticeable impact
- [ ] Low - Minor optimization opportunity

## Additional Context

<!-- Any other relevant information -->

## Checklist

- [ ] I have measured the performance issue
- [ ] I have compared with a baseline
- [ ] I have provided profiling data (if available)
- [ ] I have described the environment
