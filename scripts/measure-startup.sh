#!/bin/bash

###############################################################################
# Startup Performance Measurement Script
#
# Measures:
# - Cold start time (JVM mode)
# - Native build startup time (if available)
# - Time to first request
# - Hot reload time (dev mode)
###############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

mkdir -p "$RESULTS_DIR"

echo "========================================="
echo "Coolstore Quarkus - Startup Benchmarks"
echo "========================================="
echo ""

# Function to measure startup time
measure_startup() {
    local mode=$1
    local jar_path=$2
    local iterations=${3:-5}

    echo "Measuring $mode startup time ($iterations iterations)..."

    local total_time=0
    local times=()

    for i in $(seq 1 $iterations); do
        echo -n "  Iteration $i: "

        # Start timing
        local start=$(date +%s%N)

        # Start the application in background
        if [ "$mode" == "JVM" ]; then
            java -jar "$jar_path" > /dev/null 2>&1 &
        elif [ "$mode" == "NATIVE" ]; then
            "$jar_path" > /dev/null 2>&1 &
        fi

        local pid=$!

        # Wait for application to be ready (check health endpoint)
        local ready=false
        local timeout=60
        local elapsed=0

        while [ $ready == false ] && [ $elapsed -lt $timeout ]; do
            if curl -s http://localhost:8080/q/health/ready > /dev/null 2>&1; then
                ready=true
            else
                sleep 0.1
                elapsed=$((elapsed + 1))
            fi
        done

        # End timing
        local end=$(date +%s%N)
        local duration=$(( (end - start) / 1000000 )) # Convert to milliseconds

        echo "${duration}ms"
        times+=($duration)
        total_time=$((total_time + duration))

        # Shutdown the application
        kill $pid 2>/dev/null || true
        wait $pid 2>/dev/null || true
        sleep 2
    done

    # Calculate average
    local avg=$((total_time / iterations))

    echo ""
    echo "Results for $mode mode:"
    echo "  Individual times: ${times[@]}"
    echo "  Average startup: ${avg}ms"
    echo ""

    # Save to results file
    echo "$mode,$avg,${times[@]}" >> "$RESULTS_DIR/startup-times.csv"
}

# Function to measure time to first request
measure_first_request() {
    echo "Measuring time to first request..."

    # Build the application
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q

    local jar_path="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"

    # Start timing
    local start=$(date +%s%N)

    # Start application
    java -jar "$jar_path" > /dev/null 2>&1 &
    local pid=$!

    # Wait for first successful request
    local success=false
    while [ $success == false ]; do
        if curl -s http://localhost:8080/services/products/ > /dev/null 2>&1; then
            success=true
        else
            sleep 0.1
        fi
    done

    local end=$(date +%s%N)
    local duration=$(( (end - start) / 1000000 ))

    echo "  Time to first request: ${duration}ms"

    # Cleanup
    kill $pid 2>/dev/null || true
    wait $pid 2>/dev/null || true

    echo "first_request,$duration" >> "$RESULTS_DIR/startup-times.csv"
}

# Function to measure dev mode hot reload
measure_hot_reload() {
    echo "Measuring dev mode hot reload time..."

    cd "$PROJECT_DIR"

    # Start dev mode
    mvn quarkus:dev > /dev/null 2>&1 &
    local pid=$!

    # Wait for dev mode to be ready
    sleep 10

    # Make a small change to trigger hot reload
    local test_file="$PROJECT_DIR/src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java"
    local backup_file="$test_file.backup"

    cp "$test_file" "$backup_file"

    # Touch the file to trigger reload
    local start=$(date +%s%N)
    touch "$test_file"

    # Wait for reload to complete
    sleep 2

    local end=$(date +%s%N)
    local duration=$(( (end - start) / 1000000 ))

    echo "  Hot reload time: ${duration}ms"

    # Restore original file
    mv "$backup_file" "$test_file"

    # Cleanup
    kill $pid 2>/dev/null || true

    echo "hot_reload,$duration" >> "$RESULTS_DIR/startup-times.csv"
}

# Initialize results file
echo "mode,avg_time_ms,individual_times" > "$RESULTS_DIR/startup-times.csv"

# Build the application
echo "Building application..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q

# Measure JVM mode startup
JAR_PATH="$PROJECT_DIR/target/quarkus-app/quarkus-run.jar"
if [ -f "$JAR_PATH" ]; then
    measure_startup "JVM" "$JAR_PATH" 5
fi

# Measure native mode startup (if native binary exists)
NATIVE_PATH="$PROJECT_DIR/target/coolstore-quarkus-runner"
if [ -f "$NATIVE_PATH" ]; then
    measure_startup "NATIVE" "$NATIVE_PATH" 5
else
    echo "Native binary not found. Skipping native startup measurement."
    echo "To build native: mvn package -Pnative -DskipTests"
    echo ""
fi

# Measure time to first request
measure_first_request

# Note: Hot reload measurement commented out as it requires interactive dev mode
# measure_hot_reload

echo "========================================="
echo "Startup benchmark completed!"
echo "Results saved to: $RESULTS_DIR/startup-times.csv"
echo "========================================="
