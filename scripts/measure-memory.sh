#!/bin/bash

###############################################################################
# Memory Footprint Measurement Script
#
# Measures:
# - Initial heap usage
# - RSS (Resident Set Size)
# - Memory usage over time
# - Peak memory consumption
###############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

mkdir -p "$RESULTS_DIR"

echo "========================================="
echo "Coolstore Quarkus - Memory Benchmarks"
echo "========================================="
echo ""

# Function to get memory stats for a PID
get_memory_stats() {
    local pid=$1
    local timestamp=$2

    # Get RSS (Resident Set Size) in KB
    local rss=$(ps -o rss= -p $pid 2>/dev/null || echo "0")

    # Get heap usage from JVM (if available)
    local heap_used="N/A"
    local heap_max="N/A"

    if command -v jstat &> /dev/null; then
        # Try to get heap info using jstat
        local jstat_output=$(jstat -gc $pid 2>/dev/null || echo "")
        if [ -n "$jstat_output" ]; then
            # Parse jstat output for heap usage (this is approximate)
            heap_used=$(echo "$jstat_output" | awk 'NR==2 {print ($3+$4+$6+$8)/1024}')
            heap_max=$(echo "$jstat_output" | awk 'NR==2 {print ($1+$2+$5+$7)/1024}')
        fi
    fi

    echo "$timestamp,$pid,$rss,$heap_used,$heap_max"
}

# Function to measure memory over time
measure_memory_over_time() {
    local duration=${1:-60}  # Duration in seconds
    local interval=${2:-5}    # Sampling interval in seconds

    echo "Measuring memory usage over ${duration}s (sampling every ${interval}s)..."

    # Build and start application
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q

    local jar_path="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"

    echo "Starting application..."
    java -jar "$jar_path" > /dev/null 2>&1 &
    local pid=$!

    # Wait for application to be ready
    echo "Waiting for application to be ready..."
    while ! curl -s http://localhost:8080/q/health/ready > /dev/null 2>&1; do
        sleep 0.5
    done

    echo "Application ready. Monitoring memory..."

    # Initialize results file
    local results_file="$RESULTS_DIR/memory-over-time.csv"
    echo "timestamp,pid,rss_kb,heap_used_mb,heap_max_mb" > "$results_file"

    # Record initial memory
    local start_time=$(date +%s)
    get_memory_stats $pid 0 >> "$results_file"

    # Generate some load while monitoring
    echo "Generating load..."
    for i in $(seq 1 10); do
        curl -s http://localhost:8080/services/products/ > /dev/null &
    done

    # Monitor memory over time
    local elapsed=0
    while [ $elapsed -lt $duration ]; do
        sleep $interval
        elapsed=$((elapsed + interval))

        local timestamp=$(( $(date +%s) - start_time ))
        get_memory_stats $pid $timestamp >> "$results_file"

        # Generate periodic load
        if [ $((elapsed % 10)) -eq 0 ]; then
            for i in $(seq 1 5); do
                curl -s http://localhost:8080/services/products/ > /dev/null &
            done
        fi
    done

    # Record final memory
    local final_timestamp=$(( $(date +%s) - start_time ))
    get_memory_stats $pid $final_timestamp >> "$results_file"

    # Shutdown application
    echo "Shutting down application..."
    kill $pid 2>/dev/null || true
    wait $pid 2>/dev/null || true

    echo "Memory monitoring complete. Results saved to: $results_file"
}

# Function to measure initial memory footprint
measure_initial_memory() {
    echo "Measuring initial memory footprint..."

    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q

    local jar_path="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"

    # Start application
    java -Xmx512m -Xms256m -jar "$jar_path" > /dev/null 2>&1 &
    local pid=$!

    # Wait for application to be ready
    while ! curl -s http://localhost:8080/q/health/ready > /dev/null 2>&1; do
        sleep 0.5
    done

    # Wait a bit for JVM to stabilize
    sleep 5

    # Get memory stats
    local rss=$(ps -o rss= -p $pid | tr -d ' ')
    local rss_mb=$((rss / 1024))

    echo "  RSS: ${rss_mb} MB"

    # Save results
    local results_file="$RESULTS_DIR/initial-memory.txt"
    echo "Initial Memory Footprint - Quarkus 3.8.1" > "$results_file"
    echo "=========================================" >> "$results_file"
    echo "RSS: ${rss_mb} MB" >> "$results_file"
    echo "JVM Args: -Xmx512m -Xms256m" >> "$results_file"
    echo "Measured at: $(date)" >> "$results_file"

    # Shutdown
    kill $pid 2>/dev/null || true
    wait $pid 2>/dev/null || true

    echo "Initial memory measurement saved to: $results_file"
}

# Function to compare memory with different heap sizes
measure_heap_variations() {
    echo "Measuring memory with different heap configurations..."

    local results_file="$RESULTS_DIR/heap-variations.csv"
    echo "heap_config,rss_mb" > "$results_file"

    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q

    local jar_path="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"

    # Test different heap configurations
    local configs=("-Xmx256m -Xms128m" "-Xmx512m -Xms256m" "-Xmx1024m -Xms512m")

    for config in "${configs[@]}"; do
        echo "  Testing: $config"

        # Start application
        java $config -jar "$jar_path" > /dev/null 2>&1 &
        local pid=$!

        # Wait for ready
        while ! curl -s http://localhost:8080/q/health/ready > /dev/null 2>&1; do
            sleep 0.5
        done

        sleep 5

        # Get RSS
        local rss=$(ps -o rss= -p $pid | tr -d ' ')
        local rss_mb=$((rss / 1024))

        echo "    RSS: ${rss_mb} MB"
        echo "\"$config\",$rss_mb" >> "$results_file"

        # Shutdown
        kill $pid 2>/dev/null || true
        wait $pid 2>/dev/null || true
        sleep 2
    done

    echo "Heap variation results saved to: $results_file"
}

# Run measurements
measure_initial_memory
echo ""
measure_memory_over_time 60 5
echo ""
measure_heap_variations

echo ""
echo "========================================="
echo "Memory benchmark completed!"
echo "Results saved to: $RESULTS_DIR/"
echo "========================================="
