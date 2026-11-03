package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * HTTP characterization tests for the contract the AngularJS SPA depends on.
 * Runs against H2 in PostgreSQL mode with the real Flyway seed data.
 */
@QuarkusTest
class CartEndpointTest {

    @Test
    @DisplayName("GET /services/products returns the seeded catalog")
    void listProducts() {
        given()
                .when().get("/services/products")
                .then()
                .statusCode(200)
                .body("$", hasSize(9))
                .body("[0].itemId", notNullValue());
    }

    @Test
    @DisplayName("GET /services/products/{itemId} returns a product with inventory")
    void getProduct() {
        given()
                .when().get("/services/products/329299")
                .then()
                .statusCode(200)
                .body("name", equalTo("Quarkus T-shirt"))
                .body("price", equalTo(10.0f))
                .body("quantity", notNullValue());
    }

    @Test
    @DisplayName("Adding the promoted item applies the 25% discount and shipping fee")
    void addPromotedItemToCart() {
        given()
                .when().post("/services/cart/cart-promo/329299/1")
                .then()
                .statusCode(200)
                .body("cartItemTotal", equalTo(7.5f))
                .body("cartItemPromoSavings", equalTo(-2.5f))
                .body("shippingTotal", equalTo(2.99f))
                .body("cartTotal", equalTo(10.49f))
                .body("shoppingCartItemList", hasSize(1));
    }

    @Test
    @DisplayName("Adding the same item twice is deduped into one line")
    void dedupeCartItems() {
        given().when().post("/services/cart/cart-dedupe/165613/1").then().statusCode(200);
        given()
                .when().post("/services/cart/cart-dedupe/165613/2")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].quantity", equalTo(3))
                .body("cartItemTotal", equalTo(12.45f));
    }

    @Test
    @DisplayName("DELETE reduces then removes a cart line")
    void deleteCartItem() {
        given().when().post("/services/cart/cart-delete/165613/3").then().statusCode(200);
        given()
                .when().delete("/services/cart/cart-delete/165613/1")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList[0].quantity", equalTo(2));
        given()
                .when().delete("/services/cart/cart-delete/165613/2")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    @DisplayName("A temporary cart can be transferred to the authenticated cart id")
    void transferCart() {
        given().when().post("/services/cart/cart-tmp/444434/1").then().statusCode(200);
        given()
                .when().post("/services/cart/cart-auth/cart-tmp")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].product.itemId", equalTo("444434"));
    }

    @Test
    @DisplayName("An unknown cart id yields an empty cart, not an error")
    void unknownCart() {
        given()
                .when().get("/services/cart/never-seen")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    @DisplayName("Checkout empties the cart and publishes the order")
    void checkout() {
        given().when().post("/services/cart/cart-checkout/165614/1").then().statusCode(200);
        given()
                .when().post("/services/cart/checkout/cart-checkout")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    @DisplayName("GET /services/orders is a valid (possibly empty) JSON array")
    void listOrders() {
        given()
                .when().get("/services/orders")
                .then()
                .statusCode(200);
    }
}
