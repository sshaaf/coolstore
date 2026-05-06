package com.redhat.coolstore.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Characterization tests for OrderEndpoint.
 * These tests capture the current behavior of the order REST API.
 */
@QuarkusTest
public class OrderEndpointTest {

    @Test
    public void testListAllOrders() {
        given()
            .when()
            .get("/services/orders/")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    public void testGetOrderByIdWhenNotExists() {
        given()
            .pathParam("orderId", 999999)
            .when()
            .get("/services/orders/{orderId}")
            .then()
            .statusCode(204);
    }

    @Test
    public void testOrderEndpointAcceptsJson() {
        given()
            .accept(ContentType.JSON)
            .when()
            .get("/services/orders/")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void testOrdersListIsArray() {
        given()
            .when()
            .get("/services/orders/")
            .then()
            .statusCode(200)
            .body("$", instanceOf(java.util.List.class));
    }
}
