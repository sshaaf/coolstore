#!/bin/bash

###############################################################################
# Master Benchmark Runner
#
# Executes all benchmark suites and generates comprehensive report
###############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}"
echo "╔════════════════════════════════════════════════════════════╗"
echo "║   Red Hat Coolstore - Quarkus Migration Benchmarks        ║"
echo "║   Quarkus 3.8.1 Performance Validation                    ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Clean previous results
echo -e "${YELLOW}Cleaning previous results...${NC}"
rm -rf "$RESULTS_DIR"
mkdir -p "$RESULTS_DIR"

# Make scripts executable
chmod +x "$SCRIPT_DIR"/*.sh

echo ""
echo -e "${GREEN}Starting benchmark suite...${NC}"
echo ""

# 1. Startup Performance
echo -e "${BLUE}[1/4] Running Startup Performance Benchmarks...${NC}"
if [ -f "$SCRIPT_DIR/measure-startup.sh" ]; then
    bash "$SCRIPT_DIR/measure-startup.sh" 2>&1 | tee "$RESULTS_DIR/startup-benchmark.log"
    echo -e "${GREEN}✓ Startup benchmarks complete${NC}"
else
    echo -e "${RED}✗ Startup benchmark script not found${NC}"
fi

echo ""

# 2. Memory Footprint
echo -e "${BLUE}[2/4] Running Memory Footprint Benchmarks...${NC}"
if [ -f "$SCRIPT_DIR/measure-memory.sh" ]; then
    bash "$SCRIPT_DIR/measure-memory.sh" 2>&1 | tee "$RESULTS_DIR/memory-benchmark.log"
    echo -e "${GREEN}✓ Memory benchmarks complete${NC}"
else
    echo -e "${RED}✗ Memory benchmark script not found${NC}"
fi

echo ""

# 3. Throughput
echo -e "${BLUE}[3/4] Running Throughput Benchmarks...${NC}"
if [ -f "$SCRIPT_DIR/measure-throughput.sh" ]; then
    bash "$SCRIPT_DIR/measure-throughput.sh" 2>&1 | tee "$RESULTS_DIR/throughput-benchmark.log"
    echo -e "${GREEN}✓ Throughput benchmarks complete${NC}"
else
    echo -e "${RED}✗ Throughput benchmark script not found${NC}"
fi

echo ""

# 4. JMH Benchmarks (optional - requires running application)
echo -e "${BLUE}[4/4] JMH Benchmarks...${NC}"
echo -e "${YELLOW}Note: JMH benchmarks require manual execution${NC}"
echo -e "To run JMH benchmarks:"
echo -e "  1. Start the application: mvn quarkus:dev"
echo -e "  2. Run: mvn test -Dtest=*Benchmark"
echo -e "${GREEN}✓ JMH benchmark classes created${NC}"

echo ""
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}All automated benchmarks completed!${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
echo ""

# Generate summary report
echo -e "${BLUE}Generating benchmark report...${NC}"
bash "$SCRIPT_DIR/generate-report.sh"

echo ""
echo -e "${GREEN}Results available at: ${RESULTS_DIR}${NC}"
echo -e "${GREEN}Benchmark report: ${RESULTS_DIR}/../BENCHMARK_RESULTS.md${NC}"
echo ""
