package com.redhat.coolstore.benchmarks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for Product REST Endpoints
 *
 * Measures:
 * - Product listing endpoint throughput and latency
 * - Product detail endpoint throughput and latency
 * - Response time distribution
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ProductEndpointBenchmark {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String PRODUCTS_PATH = "/services/products/";
    private static final String PRODUCT_DETAIL_PATH = "/services/products/329299";

    @Setup(Level.Trial)
    public void setup() {
        RestAssured.baseURI = BASE_URL;
    }

    /**
     * Benchmark: GET /services/products/ - List all products
     */
    @Benchmark
    public void benchmarkListAllProducts(Blackhole blackhole) {
        String response = RestAssured
            .given()
            .when()
            .get(PRODUCTS_PATH)
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: GET /services/products/{id} - Get product by ID
     */
    @Benchmark
    public void benchmarkGetProductById(Blackhole blackhole) {
        String response = RestAssured
            .given()
            .when()
            .get(PRODUCT_DETAIL_PATH)
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: Mixed read pattern (80% list, 20% detail)
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void benchmarkMixedProductReads(Blackhole blackhole, BenchmarkState state) {
        String response;
        if (state.counter++ % 5 == 0) {
            // 20% - Get product detail
            response = RestAssured
                .given()
                .when()
                .get(PRODUCT_DETAIL_PATH)
                .then()
                .extract()
                .asString();
        } else {
            // 80% - List all products
            response = RestAssured
                .given()
                .when()
                .get(PRODUCTS_PATH)
                .then()
                .extract()
                .asString();
        }

        blackhole.consume(response);
    }

    @State(Scope.Thread)
    public static class BenchmarkState {
        int counter = 0;
    }
}
