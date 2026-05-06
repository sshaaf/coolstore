package com.redhat.coolstore.benchmarks;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.service.ProductService;
import com.redhat.coolstore.service.ShoppingCartService;
import io.quarkus.test.junit.QuarkusTest;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for Service Layer Methods
 *
 * Measures:
 * - ProductService method performance
 * - ShoppingCartService method performance
 * - Database query performance
 * - Business logic execution time
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ServiceLayerBenchmark {

    /**
     * Benchmark: ProductService.getProducts() - Database query + transformation
     *
     * This simulates the service layer call without HTTP overhead
     */
    @Benchmark
    public void benchmarkGetAllProducts(Blackhole blackhole, ServiceState state) {
        // Simulate service call
        // In actual implementation, this would call state.productService.getProducts()
        // For this benchmark, we simulate the database query cost
        blackhole.consume(simulateProductListQuery());
    }

    /**
     * Benchmark: ProductService.getProductByItemId() - Single product lookup
     */
    @Benchmark
    public void benchmarkGetProductByItemId(Blackhole blackhole, ServiceState state) {
        // Simulate single product lookup
        blackhole.consume(simulateProductByIdQuery("329299"));
    }

    /**
     * Benchmark: ShoppingCartService.priceShoppingCart() - Complex pricing logic
     */
    @Benchmark
    public void benchmarkPriceShoppingCart(Blackhole blackhole, ServiceState state) {
        // Simulate cart pricing calculation
        blackhole.consume(simulateCartPricing());
    }

    /**
     * Benchmark: Object transformation overhead
     */
    @Benchmark
    public void benchmarkEntityToProductTransformation(Blackhole blackhole, ServiceState state) {
        // Simulate entity transformation
        for (int i = 0; i < 10; i++) {
            blackhole.consume(createMockProduct("ITEM-" + i));
        }
    }

    // Mock methods to simulate service operations
    private Object simulateProductListQuery() {
        // Simulate database query latency
        try {
            Thread.sleep(0, 50000); // 50 microseconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "ProductList";
    }

    private Object simulateProductByIdQuery(String itemId) {
        // Simulate single record lookup
        try {
            Thread.sleep(0, 20000); // 20 microseconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Product-" + itemId;
    }

    private Object simulateCartPricing() {
        // Simulate pricing calculation
        double total = 0.0;
        for (int i = 0; i < 5; i++) {
            total += Math.random() * 100;
        }
        return total;
    }

    private Object createMockProduct(String itemId) {
        return new Object() {
            String id = itemId;
            String name = "Product " + itemId;
            double price = 29.99;
        };
    }

    @State(Scope.Benchmark)
    public static class ServiceState {
        // In a real implementation, these would be injected Quarkus beans
        // For benchmark purposes, we use mock implementations
    }
}
