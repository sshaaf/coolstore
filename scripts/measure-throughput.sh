#!/bin/bash

###############################################################################
# Throughput Benchmark Script
#
# Measures:
# - Requests per second for each endpoint
# - Concurrent request handling
# - Response time percentiles
# - Performance under load
###############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

mkdir -p "$RESULTS_DIR"

echo "========================================="
echo "Coolstore Quarkus - Throughput Benchmarks"
echo "========================================="
echo ""

# Check for required tools
check_dependencies() {
    if ! command -v wrk &> /dev/null && ! command -v ab &> /dev/null; then
        echo "Error: Neither 'wrk' nor 'ab' (Apache Bench) found."
        echo "Please install one of them:"
        echo "  macOS: brew install wrk"
        echo "  Linux: apt-get install apache2-utils (for ab)"
        exit 1
    fi
}

# Start the application
start_application() {
    echo "Building and starting application..."

    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q

    local jar_path="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"

    java -jar "$jar_path" > "$RESULTS_DIR/app.log" 2>&1 &
    APP_PID=$!

    echo "Waiting for application to be ready..."
    local max_wait=60
    local elapsed=0

    while ! curl -s http://localhost:8080/q/health/ready > /dev/null 2>&1; do
        if [ $elapsed -ge $max_wait ]; then
            echo "Error: Application failed to start within ${max_wait}s"
            kill $APP_PID 2>/dev/null || true
            exit 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "Application ready!"
    sleep 2
}

# Benchmark using wrk (if available)
benchmark_with_wrk() {
    local url=$1
    local name=$2
    local duration=${3:-30}
    local threads=${4:-4}
    local connections=${5:-100}

    echo "Benchmarking: $name"
    echo "  URL: $url"
    echo "  Duration: ${duration}s, Threads: $threads, Connections: $connections"

    local output_file="$RESULTS_DIR/wrk-${name}.txt"

    wrk -t$threads -c$connections -d${duration}s "$url" > "$output_file" 2>&1

    # Extract key metrics
    local rps=$(grep "Requests/sec:" "$output_file" | awk '{print $2}')
    local avg_latency=$(grep "Latency" "$output_file" | awk '{print $2}')
    local p99_latency=$(grep "99%" "$output_file" | awk '{print $2}')

    echo "  Results:"
    echo "    Requests/sec: $rps"
    echo "    Avg Latency: $avg_latency"
    echo "    99th Percentile: $p99_latency"
    echo ""

    # Append to summary
    echo "$name,$rps,$avg_latency,$p99_latency" >> "$RESULTS_DIR/throughput-summary.csv"
}

# Benchmark using Apache Bench (fallback)
benchmark_with_ab() {
    local url=$1
    local name=$2
    local requests=${3:-10000}
    local concurrency=${4:-100}

    echo "Benchmarking: $name"
    echo "  URL: $url"
    echo "  Requests: $requests, Concurrency: $concurrency"

    local output_file="$RESULTS_DIR/ab-${name}.txt"

    ab -n $requests -c $concurrency "$url" > "$output_file" 2>&1

    # Extract key metrics
    local rps=$(grep "Requests per second:" "$output_file" | awk '{print $4}')
    local mean_time=$(grep "Time per request:" "$output_file" | head -1 | awk '{print $4}')
    local p99_time=$(grep "99%" "$output_file" | awk '{print $2}')

    echo "  Results:"
    echo "    Requests/sec: $rps"
    echo "    Mean time: ${mean_time}ms"
    echo "    99th Percentile: ${p99_time}ms"
    echo ""

    # Append to summary
    echo "$name,$rps,${mean_time}ms,${p99_time}ms" >> "$RESULTS_DIR/throughput-summary.csv"
}

# Main benchmark function
run_benchmarks() {
    # Initialize summary file
    echo "endpoint,requests_per_second,avg_latency,p99_latency" > "$RESULTS_DIR/throughput-summary.csv"

    if command -v wrk &> /dev/null; then
        echo "Using wrk for benchmarks..."
        echo ""

        # Benchmark product listing
        benchmark_with_wrk "http://localhost:8080/services/products/" "products-list" 30 4 100

        # Benchmark product detail
        benchmark_with_wrk "http://localhost:8080/services/products/329299" "product-detail" 30 4 100

        # Benchmark cart retrieval
        benchmark_with_wrk "http://localhost:8080/services/cart/test-cart" "cart-get" 30 4 100

        # Benchmark order listing
        benchmark_with_wrk "http://localhost:8080/services/orders/" "orders-list" 30 4 100

        # High concurrency test
        echo "Running high concurrency test..."
        benchmark_with_wrk "http://localhost:8080/services/products/" "products-high-load" 30 8 500

    elif command -v ab &> /dev/null; then
        echo "Using Apache Bench for benchmarks..."
        echo ""

        # Benchmark product listing
        benchmark_with_ab "http://localhost:8080/services/products/" "products-list" 10000 100

        # Benchmark product detail
        benchmark_with_ab "http://localhost:8080/services/products/329299" "product-detail" 10000 100

        # Benchmark cart retrieval
        benchmark_with_ab "http://localhost:8080/services/cart/test-cart" "cart-get" 10000 100

        # Benchmark order listing
        benchmark_with_ab "http://localhost:8080/services/orders/" "orders-list" 10000 100

        # High concurrency test
        echo "Running high concurrency test..."
        benchmark_with_ab "http://localhost:8080/services/products/" "products-high-load" 10000 200
    fi
}

# Concurrent request test
test_concurrent_requests() {
    echo "Testing concurrent request handling..."

    local url="http://localhost:8080/services/products/"
    local concurrent_levels=(1 10 50 100 200 500)

    local results_file="$RESULTS_DIR/concurrency-test.csv"
    echo "concurrency_level,requests_per_second,avg_response_time" > "$results_file"

    for level in "${concurrent_levels[@]}"; do
        echo "  Testing concurrency level: $level"

        if command -v wrk &> /dev/null; then
            local output=$(wrk -t4 -c$level -d10s "$url" 2>&1)
            local rps=$(echo "$output" | grep "Requests/sec:" | awk '{print $2}')
            local avg=$(echo "$output" | grep "Latency" | awk '{print $2}')
        else
            local output=$(ab -n 5000 -c $level "$url" 2>&1)
            local rps=$(echo "$output" | grep "Requests per second:" | awk '{print $4}')
            local avg=$(echo "$output" | grep "Time per request:" | head -1 | awk '{print $4}')
        fi

        echo "    RPS: $rps, Avg: $avg"
        echo "$level,$rps,$avg" >> "$results_file"
    done

    echo "Concurrency test results saved to: $results_file"
}

# Cleanup function
cleanup() {
    echo ""
    echo "Shutting down application..."
    if [ ! -z "$APP_PID" ]; then
        kill $APP_PID 2>/dev/null || true
        wait $APP_PID 2>/dev/null || true
    fi
}

# Set trap for cleanup
trap cleanup EXIT

# Main execution
check_dependencies
start_application

echo ""
echo "Starting throughput benchmarks..."
echo ""

run_benchmarks

echo ""
test_concurrent_requests

echo ""
echo "========================================="
echo "Throughput benchmark completed!"
echo "Results saved to: $RESULTS_DIR/"
echo "Summary: $RESULTS_DIR/throughput-summary.csv"
echo "========================================="
