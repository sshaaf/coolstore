package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for ProductService.
 * Tests business logic and edge cases for product operations.
 */
@QuarkusTest
public class ProductServiceTest {

    @Inject
    ProductService productService;

    @Test
    public void testGetProducts() {
        List<Product> products = productService.getProducts();

        assertThat(products).isNotNull();
        assertThat(products).isNotEmpty();
        assertThat(products.size()).isGreaterThan(0);
    }

    @Test
    public void testGetProductsReturnsValidData() {
        List<Product> products = productService.getProducts();

        for (Product product : products) {
            assertThat(product.getItemId()).isNotNull();
            assertThat(product.getName()).isNotNull();
            assertThat(product.getPrice()).isGreaterThan(0);
        }
    }

    @Test
    public void testGetProductByValidItemId() {
        Product product = productService.getProductByItemId("329299");

        assertThat(product).isNotNull();
        assertThat(product.getItemId()).isEqualTo("329299");
        assertThat(product.getName()).isEqualTo("Quarkus T-shirt");
        assertThat(product.getPrice()).isEqualTo(10.0);
        assertThat(product.getQuantity()).isGreaterThan(0);
        assertThat(product.getLocation()).isEqualTo("Raleigh");
    }

    @Test
    public void testGetProductByInvalidItemId() {
        Product product = productService.getProductByItemId("invalid-id");

        assertThat(product).isNull();
    }

    @Test
    public void testGetProductByNullItemId() {
        // Hibernate throws exception for null ID - this is expected behavior
        try {
            Product product = productService.getProductByItemId(null);
            assertThat(product).isNull();
        } catch (IllegalArgumentException e) {
            // Expected when ID is null
            assertThat(e).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void testGetProductByEmptyItemId() {
        Product product = productService.getProductByItemId("");

        assertThat(product).isNull();
    }

    @Test
    public void testGetProductsReturnsDistinctItems() {
        List<Product> products = productService.getProducts();

        long distinctCount = products.stream()
            .map(Product::getItemId)
            .distinct()
            .count();

        assertThat(distinctCount).isEqualTo(products.size());
    }

    @Test
    public void testGetProductsIncludesKnownItems() {
        List<Product> products = productService.getProducts();

        List<String> itemIds = products.stream()
            .map(Product::getItemId)
            .toList();

        assertThat(itemIds).contains("329299", "329199", "165613");
    }

    @Test
    public void testMultipleCallsReturnSameProducts() {
        List<Product> products1 = productService.getProducts();
        List<Product> products2 = productService.getProducts();

        assertThat(products1.size()).isEqualTo(products2.size());
    }

    @Test
    public void testProductHasAllRequiredFields() {
        Product product = productService.getProductByItemId("329299");

        assertThat(product).isNotNull();
        assertThat(product.getItemId()).isNotBlank();
        assertThat(product.getName()).isNotBlank();
        assertThat(product.getPrice()).isPositive();
        assertThat(product.getQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(product.getLocation()).isNotBlank();
    }
}
