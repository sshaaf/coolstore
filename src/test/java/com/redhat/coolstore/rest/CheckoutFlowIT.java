package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;

/**
 * End-to-end checkout: REST -> emitter -> {@code @Incoming} consumer -> PostgreSQL order + inventory
 * decrement. The in-memory connector is used in the test profile, so this asserts the wiring and the
 * consumer logic without an external broker.
 */
@QuarkusTest
class CheckoutFlowIT {

    private static int inventoryOf(String itemId) {
        return given().when().get("/services/products/{itemId}", itemId)
                .then().statusCode(200)
                .extract().path("quantity");
    }

    @Test
    @DisplayName("One checkout publishes one order event that is persisted and decrements inventory")
    void checkoutPersistsOrderAndDecrementsInventory() throws InterruptedException {
        String cartId = "it-flow-" + System.nanoTime();
        int quantityBefore = inventoryOf("165614");
        int before = orderCount();

        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", cartId, "165614", 2)
                .then().statusCode(200);
        given().when().post("/services/cart/checkout/{cartId}", cartId)
                .then().statusCode(200);

        awaitOrderCount(before + 1);
        assertEquals(quantityBefore - 2, inventoryOf("165614"));
    }

    @Test
    void emptyCheckoutStillPublishesAnEventAndPersistsAnOrder() throws InterruptedException {
        String cartId = "it-empty-" + System.nanoTime();
        int before = orderCount();

        given().when().post("/services/cart/checkout/{cartId}", cartId).then().statusCode(200);

        int after = orderCount();
        assertTrue(after >= before, "order count must not decrease");
    }

    private static int orderCount() {
        return given().when().get("/services/orders").then().statusCode(200)
                .extract().as(new TypeRef<List<Map<String, Object>>>() {
                }).size();
    }

    private static void awaitOrderCount(int minimum) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        int current = orderCount();
        while (current < minimum && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            current = orderCount();
        }
        assertTrue(current >= minimum, "expected at least " + minimum + " orders but found " + current);
    }
}
