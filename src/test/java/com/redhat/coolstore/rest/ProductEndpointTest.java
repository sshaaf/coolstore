package com.redhat.coolstore.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Characterization tests for ProductEndpoint.
 * These tests capture the current behavior of the product REST API.
 */
@QuarkusTest
public class ProductEndpointTest {

    @Test
    public void testListAllProducts() {
        given()
            .when()
            .get("/services/products/")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0))
            .body("[0].itemId", notNullValue())
            .body("[0].name", notNullValue())
            .body("[0].price", notNullValue());
    }

    @Test
    public void testListAllProductsReturnsExpectedFields() {
        given()
            .when()
            .get("/services/products/")
            .then()
            .statusCode(200)
            .body("[0].itemId", notNullValue())
            .body("[0].name", notNullValue())
            .body("[0].desc", anything())
            .body("[0].price", notNullValue())
            .body("[0].location", notNullValue())
            .body("[0].quantity", notNullValue());
    }

    @Test
    public void testGetProductByValidId() {
        given()
            .pathParam("itemId", "329299")
            .when()
            .get("/services/products/{itemId}")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("itemId", equalTo("329299"))
            .body("name", equalTo("Quarkus T-shirt"))
            .body("price", equalTo(10.0f))
            .body("quantity", greaterThan(0))
            .body("location", equalTo("Raleigh"));
    }

    @Test
    public void testGetProductByAnotherValidId() {
        given()
            .pathParam("itemId", "329199")
            .when()
            .get("/services/products/{itemId}")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("itemId", equalTo("329199"))
            .body("name", equalTo("Pronounced Kubernetes"))
            .body("price", equalTo(9.0f));
    }

    @Test
    public void testGetProductByInvalidIdReturnsNull() {
        given()
            .pathParam("itemId", "invalid-id")
            .when()
            .get("/services/products/{itemId}")
            .then()
            .statusCode(204);
    }

    @Test
    public void testGetProductByNonExistentId() {
        given()
            .pathParam("itemId", "999999")
            .when()
            .get("/services/products/{itemId}")
            .then()
            .statusCode(204);
    }

    @Test
    public void testProductEndpointAcceptsJson() {
        given()
            .accept(ContentType.JSON)
            .when()
            .get("/services/products/")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void testMultipleProductsHaveDifferentIds() {
        given()
            .when()
            .get("/services/products/")
            .then()
            .statusCode(200)
            .body("itemId", hasItems("329299", "329199", "165613"));
    }

    @Test
    public void testProductPricesArePositive() {
        given()
            .when()
            .get("/services/products/")
            .then()
            .statusCode(200)
            .body("every { it.price > 0 }", is(true));
    }
}
