package com.redhat.coolstore.benchmarks;

import io.restassured.RestAssured;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH Benchmark for Shopping Cart REST Endpoints
 *
 * Measures:
 * - Cart retrieval performance
 * - Add to cart operation performance
 * - Remove from cart operation performance
 * - Checkout operation performance
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class CartEndpointBenchmark {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String CART_PATH = "/services/cart/";
    private static final AtomicInteger cartIdCounter = new AtomicInteger(0);

    @Setup(Level.Trial)
    public void setup() {
        RestAssured.baseURI = BASE_URL;
    }

    /**
     * Benchmark: GET /services/cart/{cartId} - Get cart
     */
    @Benchmark
    public void benchmarkGetCart(Blackhole blackhole, CartState state) {
        String response = RestAssured
            .given()
            .when()
            .get(CART_PATH + state.cartId)
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: POST /services/cart/{cartId}/{itemId}/{quantity} - Add to cart
     */
    @Benchmark
    public void benchmarkAddToCart(Blackhole blackhole, CartState state) {
        String response = RestAssured
            .given()
            .when()
            .post(CART_PATH + state.cartId + "/329299/1")
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: DELETE /services/cart/{cartId}/{itemId}/{quantity} - Remove from cart
     */
    @Benchmark
    public void benchmarkRemoveFromCart(Blackhole blackhole, CartState state) {
        // First add an item
        RestAssured
            .given()
            .when()
            .post(CART_PATH + state.cartId + "/329299/2")
            .then()
            .extract()
            .asString();

        // Then remove it
        String response = RestAssured
            .given()
            .when()
            .delete(CART_PATH + state.cartId + "/329299/1")
            .then()
            .extract()
            .asString();

        blackhole.consume(response);
    }

    /**
     * Benchmark: Full cart workflow (add multiple items + checkout)
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void benchmarkFullCartWorkflow(Blackhole blackhole, CartState state) {
        String cartId = "workflow-" + state.workflowCounter++;

        // Add 3 different items
        RestAssured.given().post(CART_PATH + cartId + "/329299/2");
        RestAssured.given().post(CART_PATH + cartId + "/329199/1");
        RestAssured.given().post(CART_PATH + cartId + "/165613/3");

        // Get cart
        String cart = RestAssured.given().get(CART_PATH + cartId).asString();

        // Checkout
        String response = RestAssured
            .given()
            .when()
            .post(CART_PATH + "checkout/" + cartId)
            .then()
            .extract()
            .asString();

        blackhole.consume(cart);
        blackhole.consume(response);
    }

    @State(Scope.Thread)
    public static class CartState {
        String cartId;
        int workflowCounter = 0;

        @Setup(Level.Iteration)
        public void setupCart() {
            cartId = "cart-" + cartIdCounter.incrementAndGet();
        }
    }
}
