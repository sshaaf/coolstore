package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Contract tests for the cart routes, including the {@code /cart/checkout/{cartId}} vs
 * {@code /cart/{cartId}/{tmpId}} overlap that the SPA relies on.
 */
@QuarkusTest
class CartEndpointIT {

    @Test
    void addGetAndDeleteItems() {
        String cartId = "it-cart-1";

        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", cartId, "329299", 2)
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].quantity", equalTo(2))
                .body("shoppingCartItemList[0].product.itemId", equalTo("329299"))
                .body("cartTotal", notNullValue());

        given().when().get("/services/cart/{cartId}", cartId)
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(1));

        // same product again merges the line
        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", cartId, "329299", 1)
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].quantity", equalTo(3));

        // partial delete keeps the line
        given().when().delete("/services/cart/{cartId}/{itemId}/{quantity}", cartId, "329299", 1)
                .then().statusCode(200)
                .body("shoppingCartItemList[0].quantity", equalTo(2));

        // full delete removes it
        given().when().delete("/services/cart/{cartId}/{itemId}/{quantity}", cartId, "329299", 2)
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    @DisplayName("POST /cart/{cartId}/{tmpId} transfers the anonymous cart")
    void setCartTransfersItems() {
        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", "it-anon", "329199", 2)
                .then().statusCode(200);

        given().when().post("/services/cart/{cartId}/{tmpId}", "it-user", "it-anon")
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].product.itemId", equalTo("329199"))
                .body("shoppingCartItemList[0].quantity", equalTo(2));
    }

    @Test
    @DisplayName("Checkout clears the cart and returns the re-priced cart")
    void checkoutClearsCart() {
        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", "it-checkout", "165613", 1)
                .then().statusCode(200);

        given().when().post("/services/cart/checkout/{cartId}", "it-checkout")
                .then().statusCode(200)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    void unknownProductIsRejectedWithAStableError() {
        given().when().post("/services/cart/{cartId}/{itemId}/{quantity}", "it-bad", "no-such-sku", 1)
                .then().statusCode(500)
                .body("error", equalTo("IllegalArgumentException"));
    }
}
