package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * REST contract characterization for all 9 JAX-RS endpoint methods plus the SPA/static surface
 * (Story 1 tasks 1.3/1.4/1.5, Story 6 tasks 6.2/6.3/6.6).
 */
@QuarkusTest
class RestContractTest {

    // ---------------------------------------------------------------- products (2 endpoints)

    @Test
    @DisplayName("GET /services/products returns the 9 seeded catalogue items with unchanged fields")
    void listProducts() {
        given()
                .when().get("/services/products")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(9))
                .body("[0].itemId", notNullValue())
                .body("find { it.itemId == '329299' }.name", equalTo("Quarkus T-shirt"))
                .body("find { it.itemId == '329299' }.desc", equalTo(""))
                .body("find { it.itemId == '329299' }.price", equalTo(10.0f))
                .body("find { it.itemId == '329299' }.location", equalTo("Raleigh"))
                .body("find { it.itemId == '329299' }.link", equalTo("http://maps.google.com/?q=Raleigh"))
                .body("find { it.itemId == '329299' }.quantity", equalTo(736));
    }

    @Test
    @DisplayName("GET /services/products/{itemId} returns one product; unknown id returns 204")
    void getProduct() {
        given()
                .when().get("/services/products/444435")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("itemId", equalTo("444435"))
                .body("name", equalTo("Quarkus twill cap"))
                .body("price", equalTo(13.0f));

        given()
                .when().get("/services/products/does-not-exist")
                .then()
                .statusCode(204);
    }

    // ---------------------------------------------------------------- cart (5 endpoints)

    @Test
    @DisplayName("cart add/get/delete/merge/checkout keep the documented JSON shape and pricing")
    void cartJourney() {
        String cartId = "cart-" + UUID.randomUUID();
        String tmpId = "tmp-" + UUID.randomUUID();

        // POST /services/cart/{cartId}/{itemId}/{quantity}
        JsonPath afterAdd = given()
                .when().post("/services/cart/" + cartId + "/329299/2")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].product.itemId", equalTo("329299"))
                .body("shoppingCartItemList[0].quantity", equalTo(2))
                .body("shoppingCartItemList[0].price", equalTo(10.0f)) // dedupeCartItems re-reads the catalogue price (pre-existing behaviour)
                .body("cartItemTotal", equalTo(15.0f))
                .body("cartItemPromoSavings", equalTo(-5.0f))
                .body("shippingTotal", equalTo(2.99f))
                .body("cartTotal", equalTo(17.99f))
                .extract().jsonPath();

        assertEquals(15.0, afterAdd.getDouble("cartItemTotal"), 0.0001);

        // GET /services/cart/{cartId}
        given()
                .when().get("/services/cart/" + cartId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("shoppingCartItemList", hasSize(1))
                .body("cartTotal", equalTo(17.99f));

        // DELETE /services/cart/{cartId}/{itemId}/{quantity}
        given()
                .when().delete("/services/cart/" + cartId + "/329299/1")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].quantity", equalTo(1))
                .body("cartItemTotal", equalTo(7.5f))
                .body("cartTotal", equalTo(10.49f));

        // POST /services/cart/{cartId}/{tmpId} - cart merge (login transfer)
        given()
                .when().post("/services/cart/" + tmpId + "/" + cartId)
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].product.itemId", equalTo("329299"))
                .body("shoppingCartItemList[0].quantity", equalTo(1));

        // POST /services/cart/checkout/{cartId}
        given()
                .when().post("/services/cart/checkout/" + tmpId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("shoppingCartItemList", hasSize(0))
                .body("cartItemTotal", equalTo(0.0f))
                .body("cartTotal", equalTo(0.0f));
    }

    @Test
    @DisplayName("dedupeCartItems merges repeated item ids into one line")
    void cartDedupeOnAdd() {
        String cartId = "dedupe-" + UUID.randomUUID();

        given().when().post("/services/cart/" + cartId + "/444435/2").then().statusCode(200);
        given()
                .when().post("/services/cart/" + cartId + "/444435/3")
                .then()
                .statusCode(200)
                .body("shoppingCartItemList", hasSize(1))
                .body("shoppingCartItemList[0].quantity", equalTo(5));
    }

    // ---------------------------------------------------------------- orders (2 endpoints)

    @Test
    @DisplayName("GET /services/orders and /services/orders/{id} answer with a JSON array/object")
    void orders() {
        given()
                .when().get("/services/orders")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);

        given()
                .when().get("/services/orders/999999")
                .then()
                .statusCode(204);
    }

    // ---------------------------------------------------------------- SPA + static assets

    @Test
    @DisplayName("GET / returns the SPA shell (static index.html, no JSP)")
    void spaShell() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("ng-view"))
                .body(containsString("/bower_components/angular/angular.min.js"));
    }

    @Test
    @DisplayName("static assets under /app, /partials and /bower_components resolve")
    void staticAssets() {
        given().when().get("/app/app.js").then().statusCode(200).body(containsString("angular.module"));
        given().when().get("/app/services/cart.js").then().statusCode(200);
        given().when().get("/partials/home.html").then().statusCode(200);
        given().when().get("/bower_components/angular/angular.min.js").then().statusCode(200);
    }

    @Test
    @DisplayName("/coolstore.json is generated from configuration")
    void coolstoreConfig() {
        given()
                .when().get("/coolstore.json")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("MONOLITH", equalTo(true))
                .body("API_ENDPOINT", equalTo(""))
                .body("SECURE_API_ENDPOINT", equalTo(""))
                .body("SSO_ENABLED", equalTo(false)); // false in the test profile
    }

    @Test
    @DisplayName("/keycloak.json is generated from configuration and contains no localhost literal")
    void keycloakConfig() {
        given()
                .when().get("/keycloak.json")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("realm", equalTo("eap"))
                .body("resource", equalTo("eap-app"))
                .body("public-client", equalTo(true))
                .body("auth-server-url", equalTo(""));
    }

    @Test
    @DisplayName("the same documents are available under /services/config")
    void configEndpoint() {
        given().when().get("/services/config/coolstore.json").then().statusCode(200).body("MONOLITH", equalTo(true));
        given().when().get("/services/config/keycloak.json").then().statusCode(200).body("realm", equalTo("eap"));
    }

    // ---------------------------------------------------------------- health (replaces health.jsp)

    @Test
    @DisplayName("/q/health/live, /ready and /started replace health.jsp")
    void health() {
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
        given().when().get("/q/health/started").then().statusCode(200);
        given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }
}
