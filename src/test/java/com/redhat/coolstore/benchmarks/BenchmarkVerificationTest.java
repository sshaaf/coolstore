package com.redhat.coolstore.benchmarks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification test to ensure benchmark infrastructure is properly configured
 *
 * This test validates that:
 * - JMH dependencies are available
 * - Benchmark classes are properly structured
 * - No compilation errors in benchmarks
 */
public class BenchmarkVerificationTest {

    @Test
    public void testJMHDependenciesAvailable() {
        try {
            Class.forName("org.openjdk.jmh.annotations.Benchmark");
            Class.forName("org.openjdk.jmh.runner.Runner");
            assertTrue(true, "JMH dependencies are available");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("JMH dependencies not found. Check pom.xml", e);
        }
    }

    @Test
    public void testBenchmarkClassesExist() {
        try {
            Class.forName("com.redhat.coolstore.benchmarks.ProductEndpointBenchmark");
            Class.forName("com.redhat.coolstore.benchmarks.CartEndpointBenchmark");
            Class.forName("com.redhat.coolstore.benchmarks.OrderEndpointBenchmark");
            Class.forName("com.redhat.coolstore.benchmarks.ServiceLayerBenchmark");
            assertTrue(true, "All benchmark classes exist");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Benchmark classes not found", e);
        }
    }

    @Test
    public void testBenchmarkRunnerExists() {
        try {
            Class.forName("com.redhat.coolstore.benchmarks.BenchmarkRunner");
            assertTrue(true, "BenchmarkRunner exists");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("BenchmarkRunner not found", e);
        }
    }

    @Test
    public void testRestAssuredAvailable() {
        try {
            Class.forName("io.restassured.RestAssured");
            assertTrue(true, "RestAssured is available for benchmarks");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("RestAssured not found. Required for REST benchmarks", e);
        }
    }
}
