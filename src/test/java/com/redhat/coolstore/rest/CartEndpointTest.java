package com.redhat.coolstore.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Characterization tests for CartEndpoint.
 * These tests capture the current behavior of the shopping cart REST API.
 */
@QuarkusTest
public class CartEndpointTest {

    @Test
    public void testGetEmptyCart() {
        given()
            .pathParam("cartId", "test-cart-1")
            .when()
            .get("/services/cart/{cartId}")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("cartItemTotal", equalTo(0.0f))
            .body("cartTotal", equalTo(0.0f))
            .body("shoppingCartItemList.size()", equalTo(0));
    }

    @Test
    public void testAddItemToCart() {
        given()
            .pathParam("cartId", "test-cart-2")
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("shoppingCartItemList.size()", equalTo(1))
            .body("shoppingCartItemList[0].product.itemId", equalTo("329299"))
            .body("shoppingCartItemList[0].quantity", equalTo(1))
            .body("cartItemTotal", greaterThanOrEqualTo(0.0f));
    }

    @Test
    public void testAddMultipleItemsToCart() {
        String cartId = "test-cart-3";

        // Add first item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200);

        // Add second item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329199")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(2));
    }

    @Test
    public void testAddSameItemTwiceDeduplicates() {
        String cartId = "test-cart-4";

        // Add item first time
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200);

        // Add same item again
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(1))
            .body("shoppingCartItemList[0].quantity", equalTo(3));
    }

    @Test
    public void testDeleteItemFromCart() {
        String cartId = "test-cart-5";

        // Add item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 3)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}");

        // Delete partial quantity
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .delete("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(1))
            .body("shoppingCartItemList[0].quantity", equalTo(2));
    }

    @Test
    public void testDeleteAllItemQuantityRemovesFromCart() {
        String cartId = "test-cart-6";

        // Add item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}");

        // Delete all quantity
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .delete("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0));
    }

    @Test
    public void testDeleteMoreThanAvailableRemovesItem() {
        String cartId = "test-cart-7";

        // Add item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}");

        // Delete more than available
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 5)
            .when()
            .delete("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0));
    }

    @Test
    public void testCheckoutEmptiesCart() {
        String cartId = "test-cart-8";

        // Add items
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}");

        // Checkout
        given()
            .pathParam("cartId", cartId)
            .when()
            .post("/services/cart/checkout/{cartId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0))
            .body("cartTotal", equalTo(0.0f));
    }

    @Test
    public void testSetCartFromTempCart() {
        String cartId = "test-cart-9";
        String tmpCartId = "test-cart-tmp-9";

        // Add items to temp cart
        given()
            .pathParam("cartId", tmpCartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}");

        // Set main cart from temp cart
        given()
            .pathParam("cartId", cartId)
            .pathParam("tmpId", tmpCartId)
            .when()
            .post("/services/cart/{cartId}/{tmpId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(1))
            .body("shoppingCartItemList[0].product.itemId", equalTo("329299"));
    }

    @Test
    public void testCartCalculatesShipping() {
        String cartId = "test-cart-10";

        // Add item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shippingTotal", greaterThanOrEqualTo(0.0f))
            .body("cartTotal", greaterThanOrEqualTo(0.0f));
    }

    @Test
    public void testCartAppliesPromotions() {
        String cartId = "test-cart-11";

        // Item 329299 has a 25% promotion
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList[0].promoSavings", not(equalTo(0.0f)))
            .body("cartItemPromoSavings", not(equalTo(0.0f)));
    }
}
