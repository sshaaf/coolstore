package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Catalogue contract: 9 seeded products, full product shape, and 204 for an unknown SKU
 * (the monolith returned {@code null}, which JAX-RS renders as 204).
 */
@QuarkusTest
class ProductEndpointIT {

    @Test
    void listsSeededProducts() {
        given().when().get("/services/products")
                .then().statusCode(200)
                .body("size()", equalTo(9))
                .body("[0].itemId", org.hamcrest.Matchers.notNullValue())
                .body("find { it.itemId == '329299' }.name", equalTo("Quarkus T-shirt"))
                .body("find { it.itemId == '329299' }.quantity", greaterThanOrEqualTo(0));
    }

    @Test
    void getsSingleProduct() {
        given().when().get("/services/products/{itemId}", "329299")
                .then().statusCode(200)
                .body("itemId", equalTo("329299"))
                .body("name", equalTo("Quarkus T-shirt"))
                .body("price", equalTo(10.0f))
                .body("location", equalTo("Raleigh"));
    }

    @Test
    @DisplayName("Unknown SKU returns 204, matching the monolith")
    void unknownProductReturnsNoContent() {
        given().when().get("/services/products/{itemId}", "does-not-exist")
                .then().statusCode(204);
    }
}
