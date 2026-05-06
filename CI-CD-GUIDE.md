# CI/CD Guide for Coolstore Quarkus

## Overview

This document provides comprehensive guidance for the CI/CD infrastructure of the Coolstore Quarkus application after successful migration from JavaEE 7 to Quarkus 3.8.1.

## Table of Contents

- [Pipeline Overview](#pipeline-overview)
- [Workflows](#workflows)
- [Running Pipelines Locally](#running-pipelines-locally)
- [Interpreting Results](#interpreting-results)
- [Adding New Checks](#adding-new-checks)
- [Troubleshooting](#troubleshooting)
- [Configuration](#configuration)

## Pipeline Overview

The CI/CD infrastructure consists of several GitHub Actions workflows:

### Main Workflows

1. **CI Pipeline** (`ci.yml`) - Continuous Integration
2. **Performance Testing** (`performance.yml`) - Performance benchmarks
3. **Security Scanning** (`security.yml`) - Security analysis
4. **Deploy to Staging** (`deploy-staging.yml`) - Staging deployment
5. **Release Pipeline** (`release.yml`) - Production releases
6. **Quality Gates** (`quality-gates.yml`) - Quality validation

### Pipeline Architecture

```
┌─────────────────┐
│   Push/PR       │
└────────┬────────┘
         │
    ┌────▼────┐
    │   CI    │──────┐
    └────┬────┘      │
         │           │
    ┌────▼────────┐  │
    │ Quality     │  │
    │ Gates       │  │
    └────┬────────┘  │
         │           │
    ┌────▼────────┐  │    ┌──────────────┐
    │ Performance │  ├───►│  Security    │
    │ Tests       │  │    │  Scanning    │
    └─────────────┘  │    └──────────────┘
                     │
            ┌────────▼────────┐
            │   All Passed?   │
            └────────┬────────┘
                     │
         ┌───────────┴───────────┐
         │                       │
    ┌────▼────┐           ┌──────▼──────┐
    │ Staging │           │   Manual    │
    │ Deploy  │           │   Review    │
    └────┬────┘           └──────┬──────┘
         │                       │
    ┌────▼───────────────────────▼────┐
    │     Production Release          │
    └─────────────────────────────────┘
```

## Workflows

### 1. CI Pipeline (`ci.yml`)

**Triggers:**
- Push to any branch
- Pull request to `main` or `quarkus-migration`

**Jobs:**
- **Build**: Compile, test, and build JAR
- **Quality Gates**: Verify quality thresholds
- **Status Check**: Overall CI status validation

**Key Features:**
- Maven dependency caching
- JaCoCo code coverage
- Codecov integration
- Artifact uploading

**Configuration:**
```yaml
env:
  JAVA_VERSION: '17'
  MAVEN_OPTS: '-Xmx2g -XX:MaxMetaspaceSize=512m'
```

### 2. Performance Testing (`performance.yml`)

**Triggers:**
- Pull request to main branches
- Manual dispatch

**Jobs:**
- **JMH Benchmarks**: Microbenchmarks
- **Startup Benchmarks**: Startup time measurement
- **Memory Benchmarks**: Memory usage analysis
- **Throughput Tests**: Request throughput testing
- **Baseline Comparison**: Compare with baseline metrics

**Example Results:**
```json
{
  "startup_time_seconds": 2.345,
  "memory_rss_mb": 150.5,
  "requests_per_second": 5000
}
```

### 3. Security Scanning (`security.yml`)

**Triggers:**
- Pull request to main branches
- Daily at 2 AM UTC
- Manual dispatch

**Jobs:**
- **Dependency Scan**: OWASP Dependency Check
- **SAST**: Static Application Security Testing (SpotBugs)
- **CodeQL Analysis**: GitHub CodeQL scanning
- **Secret Scan**: Gitleaks secret detection
- **Container Scan**: Trivy image scanning

**Quality Thresholds:**
- Critical vulnerabilities: 0
- High vulnerabilities: <5
- Secrets: 0

### 4. Deploy to Staging (`deploy-staging.yml`)

**Triggers:**
- Push to `main` branch
- Manual dispatch

**Jobs:**
- **Build and Push**: Container image build/push
- **Deploy Staging**: Kubernetes deployment
- **Smoke Tests**: Basic functionality validation
- **Notify**: Deployment notifications

**Environments:**
- Name: `staging`
- URL: Configured via `STAGING_URL` variable

### 5. Release Pipeline (`release.yml`)

**Triggers:**
- Tag push (`v*`)
- Manual dispatch

**Jobs:**
- **Prepare Release**: Version and changelog
- **Build Artifacts**: JVM and native builds
- **Build Container Images**: Multi-architecture images
- **Create GitHub Release**: Release creation
- **Deploy Production**: Production deployment
- **Notify Release**: Release notifications

**Versioning:**
- Semantic versioning (e.g., v1.0.0)
- Automatic changelog generation
- SHA256 checksums

### 6. Quality Gates (`quality-gates.yml`)

**Triggers:**
- Pull request to main branches
- Manual dispatch

**Quality Thresholds:**
- Code coverage: ≥80%
- Test success rate: ≥95%
- Critical vulnerabilities: 0
- Performance regression: ≤5%

**Jobs:**
- Code Coverage Gate
- Test Quality Gate
- Build Quality Gate
- Security Quality Gate
- Performance Quality Gate
- Quality Summary

## Running Pipelines Locally

### Prerequisites

```bash
# Install required tools
brew install maven
brew install docker
brew install apache2-utils  # for ab (Apache Bench)

# Ensure Java 17 is installed
java -version
```

### Run CI Pipeline Locally

```bash
# Set environment variables
export CI=true
export JAVA_HOME=/path/to/java-17

# Run full build with tests
mvn clean verify -Pci

# Run tests with coverage
mvn clean test jacoco:report

# Check coverage threshold
mvn jacoco:check
```

### Run Performance Tests Locally

```bash
# Build application
mvn clean package -DskipTests

# Run JMH benchmarks
mvn test-compile exec:java \
  -Dexec.mainClass=org.openjdk.jmh.Main \
  -Dexec.classpathScope=test \
  -Dexec.args="com.redhat.coolstore.benchmarks"

# Run startup benchmark
time java -jar target/coolstore-quarkus.jar

# Run throughput test
java -jar target/coolstore-quarkus.jar &
sleep 20
ab -n 10000 -c 100 http://localhost:8080/api/products
```

### Run Security Scans Locally

```bash
# OWASP Dependency Check
mvn org.owasp:dependency-check-maven:check

# SpotBugs
mvn com.github.spotbugs:spotbugs-maven-plugin:check

# Gitleaks
docker run --rm -v $(pwd):/repo zricethezav/gitleaks:latest \
  detect --source /repo -v
```

### Test Docker Build Locally

```bash
# Build JVM image
docker build -f src/main/docker/Dockerfile.jvm -t coolstore-quarkus:local .

# Run container
docker run -p 8080:8080 coolstore-quarkus:local

# Test endpoints
curl http://localhost:8080/api/products
curl http://localhost:8080/q/health/ready
```

### Run with Docker Compose

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f coolstore

# Access services
# - Application: http://localhost:8080
# - Prometheus: http://localhost:9090
# - Grafana: http://localhost:3000 (admin/admin)

# Stop services
docker-compose down
```

## Interpreting Results

### CI Pipeline Results

**Successful Build:**
```
✓ Build and Test - All tests passed
✓ Quality Gates - Coverage: 85% (threshold: 80%)
✓ CI Status Check - All checks passed
```

**Failed Build:**
```
✗ Build and Test - 3 tests failed
✗ Quality Gates - Coverage: 75% (threshold: 80%)
```

### Coverage Report

Navigate to: `target/site/jacoco/index.html`

**Key Metrics:**
- **Line Coverage**: Percentage of lines executed
- **Branch Coverage**: Percentage of branches tested
- **Complexity**: Cyclomatic complexity

**Quality Targets:**
- Line coverage: ≥80%
- Branch coverage: ≥75%
- Complexity: <10 per method

### Performance Results

**Benchmark Metrics:**
- **Throughput**: ops/s (higher is better)
- **Average Time**: μs/op (lower is better)
- **P95/P99**: 95th/99th percentile latency

**Acceptable Regression:**
- ≤5% performance degradation
- ≤10% memory increase
- ≤2s startup time increase

### Security Scan Results

**Severity Levels:**
- **Critical**: Must fix immediately
- **High**: Fix before release
- **Medium**: Fix in next sprint
- **Low**: Fix when possible

**Action Items:**
1. Review dependency-check-report.html
2. Update vulnerable dependencies
3. Add suppressions for false positives
4. Re-run security scan

## Adding New Checks

### Add a New GitHub Actions Workflow

1. Create new workflow file:
```bash
.github/workflows/my-new-check.yml
```

2. Define workflow:
```yaml
name: My New Check
on:
  pull_request:
    branches: [main]

jobs:
  my-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run my check
        run: echo "Running check"
```

3. Add to required checks (repository settings):
   - Settings → Branches → Branch protection rules
   - Add "My New Check" to required status checks

### Add a New Quality Gate

Edit `.github/workflows/quality-gates.yml`:

```yaml
  my-quality-gate:
    name: My Quality Gate
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Run quality check
        run: |
          # Your quality check logic
          if [ condition ]; then
            echo "✅ Quality gate passed"
          else
            echo "❌ Quality gate failed"
            exit 1
          fi
```

### Add a New Performance Test

1. Create benchmark class:
```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
public class MyBenchmark {
    @Benchmark
    public void testMethod() {
        // Benchmark code
    }
}
```

2. Run in CI:
```yaml
- name: Run my benchmark
  run: |
    mvn test-compile exec:java \
      -Dexec.mainClass=org.openjdk.jmh.Main \
      -Dexec.args="MyBenchmark"
```

## Troubleshooting

### Common Issues

#### 1. Tests Failing in CI but Passing Locally

**Possible Causes:**
- Timing issues
- Environment differences
- Parallel execution

**Solutions:**
```bash
# Run tests with CI profile locally
mvn clean test -Pci

# Check for timing-sensitive tests
grep -r "Thread.sleep" src/test

# Disable parallel execution temporarily
mvn test -Dmaven.test.parallel=none
```

#### 2. Coverage Threshold Not Met

**Solutions:**
```bash
# Generate coverage report
mvn clean test jacoco:report

# View report
open target/site/jacoco/index.html

# Add more tests for uncovered code
# Or adjust threshold in pom.xml (not recommended)
```

#### 3. Container Build Failing

**Solutions:**
```bash
# Build locally with verbose output
docker build -f src/main/docker/Dockerfile.jvm -t test:latest . --progress=plain

# Check if JAR exists
ls -lh target/coolstore-quarkus.jar

# Rebuild JAR
mvn clean package -DskipTests
```

#### 4. Dependency Vulnerabilities

**Solutions:**
```bash
# Update dependencies
mvn versions:display-dependency-updates

# Update specific dependency
mvn versions:use-latest-versions -Dincludes=groupId:artifactId

# Add suppression for false positives
# Edit: .github/dependency-check-suppressions.xml
```

#### 5. Performance Regression Detected

**Investigation Steps:**
```bash
# Compare benchmark results
diff baseline-benchmarks.json current-benchmarks.json

# Profile the application
java -agentlib:hprof=cpu=samples,depth=10 -jar target/coolstore-quarkus.jar

# Check for memory leaks
jmap -dump:live,format=b,file=heap.bin <pid>
```

### Debug GitHub Actions

**Enable debug logging:**

1. Set repository secrets:
   - `ACTIONS_STEP_DEBUG=true`
   - `ACTIONS_RUNNER_DEBUG=true`

2. Re-run workflow with debug logs enabled

**Download artifacts:**
```bash
# Using GitHub CLI
gh run download <run-id>

# Or via web interface:
# Actions → Workflow run → Artifacts
```

## Configuration

### Environment Variables

**Required:**
- `JAVA_VERSION`: Java version (17)
- `MAVEN_CLI_OPTS`: Maven options

**Optional:**
- `CODECOV_TOKEN`: Codecov upload token
- `SLACK_WEBHOOK_URL`: Slack notifications
- `STAGING_URL`: Staging environment URL
- `PRODUCTION_URL`: Production environment URL

### Repository Secrets

Configure in: Settings → Secrets and variables → Actions

**Required Secrets:**
- `GITHUB_TOKEN`: Auto-provided by GitHub
- `CODECOV_TOKEN`: For coverage reporting (optional)

**Optional Secrets:**
- `SLACK_WEBHOOK_URL`: Slack notifications
- `AZUREAPPSERVICE_PUBLISHPROFILE_*`: Azure deployment
- Container registry credentials (if not using ghcr.io)

### Repository Variables

Configure in: Settings → Secrets and variables → Actions

**Variables:**
- `STAGING_URL`: https://staging.coolstore.example.com
- `PRODUCTION_URL`: https://coolstore.example.com

### Branch Protection Rules

**Recommended Settings:**

1. Go to: Settings → Branches → Add rule

2. Branch name pattern: `main`

3. Enable:
   - ✅ Require a pull request before merging
   - ✅ Require status checks to pass before merging
     - CI Pipeline
     - Quality Gates
     - Security Scanning
   - ✅ Require conversation resolution before merging
   - ✅ Do not allow bypassing the above settings

## Status Badges

Add to README.md:

```markdown
![CI Pipeline](https://github.com/sshaaf/coolstore/workflows/CI%20Pipeline/badge.svg)
![Quality Gates](https://github.com/sshaaf/coolstore/workflows/Quality%20Gates/badge.svg)
![Security](https://github.com/sshaaf/coolstore/workflows/Security%20Scanning/badge.svg)
[![codecov](https://codecov.io/gh/sshaaf/coolstore/branch/main/graph/badge.svg)](https://codecov.io/gh/sshaaf/coolstore)
```

## Monitoring and Observability

### Prometheus Metrics

Access metrics at: `http://localhost:8080/q/metrics`

**Key Metrics:**
- `http_server_requests_seconds`: HTTP request metrics
- `jvm_memory_used_bytes`: JVM memory usage
- `jvm_gc_pause_seconds`: GC pause times
- `process_cpu_usage`: CPU usage

### Grafana Dashboards

Access Grafana at: `http://localhost:3000` (admin/admin)

**Pre-configured Dashboards:**
- JVM metrics
- HTTP request metrics
- Database connection pool
- Custom application metrics

### Alerts

Prometheus alerts configured in: `monitoring/alerts.yml`

**Alert Conditions:**
- Application down for >1 minute
- Error rate >10%
- Response time >1 second (P95)
- Memory usage >90%
- CPU usage >90%

## Best Practices

1. **Always run tests locally before pushing**
2. **Keep workflows fast** (<10 minutes for CI)
3. **Cache dependencies** to speed up builds
4. **Fail fast** - run quickest checks first
5. **Monitor metrics** - track build times and success rates
6. **Document changes** - update this guide when adding checks
7. **Version lock** - pin action versions for stability
8. **Secrets management** - never commit secrets
9. **Review security scans** - don't ignore warnings
10. **Performance baseline** - maintain performance benchmarks

## Next Steps

1. ✅ Configure repository secrets
2. ✅ Set up branch protection rules
3. ✅ Add status badges to README
4. ✅ Configure Codecov integration
5. ✅ Set up Slack notifications
6. ✅ Deploy to staging environment
7. ✅ Run first production release
8. ✅ Monitor metrics and alerts

## Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Quarkus CI/CD Guide](https://quarkus.io/guides/continuous-testing)
- [Maven Release Plugin](https://maven.apache.org/maven-release/maven-release-plugin/)
- [JaCoCo Documentation](https://www.jacoco.org/jacoco/trunk/doc/)
- [OWASP Dependency Check](https://owasp.org/www-project-dependency-check/)
- [JMH Benchmarks](https://github.com/openjdk/jmh)

## Support

For issues or questions:
- Open an issue: https://github.com/sshaaf/coolstore/issues
- Contact: sshaaf@redhat.com
- Slack: #coolstore-support

---

**Last Updated:** 2026-05-06
**Version:** 1.0.0
**Maintained by:** CI Integration Agent
