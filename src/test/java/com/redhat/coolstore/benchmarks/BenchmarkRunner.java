package com.redhat.coolstore.benchmarks;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark Runner
 *
 * This class provides a convenient way to run all benchmarks or specific benchmark suites.
 *
 * Usage:
 * 1. Start the application: mvn quarkus:dev
 * 2. Run this main method from your IDE or via Maven
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            // Specify which benchmarks to run (can be regex pattern)
            .include(".*Benchmark.*")

            // Configure warmup
            .warmupIterations(3)
            .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(5))

            // Configure measurement
            .measurementIterations(5)
            .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(10))

            // Number of forks (separate JVM processes)
            .forks(1)

            // Number of threads
            .threads(1)

            // Output format
            .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
            .result("benchmark-results/jmh-results.json")

            // Additional options
            .shouldDoGC(true)
            .shouldFailOnError(true)

            .build();

        new Runner(opt).run();
    }

    /**
     * Run only Product benchmarks
     */
    public static void runProductBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(ProductEndpointBenchmark.class.getSimpleName())
            .warmupIterations(2)
            .measurementIterations(3)
            .forks(1)
            .build();

        new Runner(opt).run();
    }

    /**
     * Run only Cart benchmarks
     */
    public static void runCartBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(CartEndpointBenchmark.class.getSimpleName())
            .warmupIterations(2)
            .measurementIterations(3)
            .forks(1)
            .build();

        new Runner(opt).run();
    }

    /**
     * Run only Service layer benchmarks
     */
    public static void runServiceBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(ServiceLayerBenchmark.class.getSimpleName())
            .warmupIterations(2)
            .measurementIterations(3)
            .forks(1)
            .build();

        new Runner(opt).run();
    }

    /**
     * Quick benchmark run for smoke testing
     */
    public static void runQuickBenchmark() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(".*Benchmark.*")
            .warmupIterations(1)
            .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(2))
            .measurementIterations(2)
            .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(5))
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
