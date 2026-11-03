package com.redhat.coolstore.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Exercises the Kafka consumer bodies directly (the messaging connector is
 * in-memory in the test profile, so no broker is required).
 */
@QuarkusTest
class OrderMessageConsumerTest {

    private static final String ORDER_JSON = """
            {"orderValue":10.49,"customerName":"Karl Svensson","customerEmail":"karl@gmail.com",
             "retailPrice":10.0,"discount":-2.5,"shippingFee":2.99,"shippingDiscount":0.0,
             "items":[{"productSku":"329299","quantity":2}]}
            """;

    @Inject
    OrderMessageConsumer orderMessageConsumer;

    @Inject
    InventoryNotificationConsumer inventoryNotificationConsumer;

    @Inject
    CatalogService catalogService;

    @Test
    @DisplayName("Consuming an order persists it and decrements inventory")
    void consumeOrder() {
        int before = catalogService.getCatalogItemById("329299").getInventory().getQuantity();

        orderMessageConsumer.onMessage(ORDER_JSON);

        assertNotNull(catalogService.getCatalogItemById("329299"));
        assertEquals(before - 2, catalogService.getCatalogItemById("329299").getInventory().getQuantity());
        given()
                .when().get("/services/orders")
                .then()
                .statusCode(200)
                .body("find { it.customerEmail == 'karl@gmail.com' }.discount", equalTo(-2.5f));
    }

    @Test
    @DisplayName("Inventory notification handler tolerates an order for an unknown item")
    void inventoryNotificationUnknownItem() {
        inventoryNotificationConsumer.onMessage("""
                {"orderValue":1,"customerName":"A","customerEmail":"a@b.c","retailPrice":1,
                 "discount":0,"shippingFee":0,"shippingDiscount":0,
                 "items":[{"productSku":"does-not-exist","quantity":1}]}
                """);
    }
}
