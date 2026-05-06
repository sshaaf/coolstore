package com.redhat.coolstore.benchmarks;

import io.restassured.RestAssured;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for Order REST Endpoints
 *
 * Measures:
 * - Order listing performance
 * - Order detail retrieval performance
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class OrderEndpointBenchmark {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String ORDERS_PATH = "/services/orders/";

    @Setup(Level.Trial)
    public void setup() {
        RestAssured.baseURI = BASE_URL;

        // Create some test orders by checking out carts
        for (int i = 0; i < 5; i++) {
            String cartId = "test-cart-" + i;
            RestAssured.given().post("/services/cart/" + cartId + "/329299/2");
            RestAssured.given().post("/services/cart/checkout/" + cartId);
        }
    }

    /**
     * Benchmark: GET /services/orders/ - List all orders
     */
    @Benchmark
    public void benchmarkListAllOrders(Blackhole blackhole) {
        String response = RestAssured
            .given()
            .when()
            .get(ORDERS_PATH)
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: GET /services/orders/{orderId} - Get order by ID
     */
    @Benchmark
    public void benchmarkGetOrderById(Blackhole blackhole) {
        String response = RestAssured
            .given()
            .when()
            .get(ORDERS_PATH + "1")
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }
}
