package com.redhat.coolstore.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Integration tests for the complete cart checkout flow.
 * Tests end-to-end scenarios from adding items to checking out.
 */
@QuarkusTest
public class CartCheckoutIntegrationTest {

    @Test
    public void testCompleteCheckoutFlow() {
        String cartId = "integration-cart-1";

        // Step 1: Get empty cart
        given()
            .pathParam("cartId", cartId)
            .when()
            .get("/services/cart/{cartId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0));

        // Step 2: Add item to cart
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(1))
            .body("cartTotal", greaterThan(0.0f));

        // Step 3: Add another item
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329199")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(2));

        // Step 4: Verify cart state before checkout
        given()
            .pathParam("cartId", cartId)
            .when()
            .get("/services/cart/{cartId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(2))
            .body("cartTotal", greaterThan(0.0f));

        // Step 5: Checkout cart
        given()
            .pathParam("cartId", cartId)
            .when()
            .post("/services/cart/checkout/{cartId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0))
            .body("cartTotal", equalTo(0.0f));

        // Step 6: Verify cart is empty after checkout
        given()
            .pathParam("cartId", cartId)
            .when()
            .get("/services/cart/{cartId}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0))
            .body("cartTotal", equalTo(0.0f));
    }

    @Test
    public void testShoppingFlowWithPromotions() {
        String cartId = "integration-cart-2";

        // Add promotional item (329299 has 25% off)
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("cartItemPromoSavings", greaterThan(0.0f))
            .body("shoppingCartItemList[0].promoSavings", greaterThan(0.0f));
    }

    @Test
    public void testCartModificationFlow() {
        String cartId = "integration-cart-3";

        // Add items
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 5)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200);

        // Remove some quantity
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 2)
            .when()
            .delete("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList[0].quantity", equalTo(3));

        // Remove remaining
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 3)
            .when()
            .delete("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(0));
    }

    @Test
    public void testHighValueCartWithFreeShipping() {
        String cartId = "integration-cart-4";

        // Add enough items to exceed $75 threshold for free shipping
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "165614")
            .pathParam("quantity", 6)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shippingTotal", equalTo(0.0f))
            .body("shippingPromoSavings", greaterThan(0.0f));
    }

    @Test
    public void testProductCatalogIntegration() {
        // Verify product exists
        given()
            .pathParam("itemId", "329299")
            .when()
            .get("/services/products/{itemId}")
            .then()
            .statusCode(200)
            .body("itemId", equalTo("329299"))
            .body("name", notNullValue());

        // Add that product to cart
        String cartId = "integration-cart-5";
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329299")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList[0].product.name", equalTo("Quarkus T-shirt"));
    }

    @Test
    public void testCartPricingAccuracy() {
        String cartId = "integration-cart-6";

        // Add item with known price (329199 = $9.00)
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "329199")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList[0].product.price", equalTo(9.0f))
            .body("cartItemTotal", equalTo(9.0f))
            .body("shippingTotal", equalTo(2.99f))
            .body("cartTotal", equalTo(11.99f));
    }

    @Test
    public void testMultipleItemsWithDifferentPrices() {
        String cartId = "integration-cart-7";

        // Add multiple different items
        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "165613")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200);

        given()
            .pathParam("cartId", cartId)
            .pathParam("itemId", "165614")
            .pathParam("quantity", 1)
            .when()
            .post("/services/cart/{cartId}/{itemId}/{quantity}")
            .then()
            .statusCode(200)
            .body("shoppingCartItemList.size()", equalTo(2))
            .body("cartItemTotal", greaterThan(0.0f));
    }
}
