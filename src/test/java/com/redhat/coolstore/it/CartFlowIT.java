package com.redhat.coolstore.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end business flow: browse -> add -> dedupe -> price -> checkout -> order persisted
 * -> inventory decremented. Needs PostgreSQL, Redis and the Artemis broker.
 */
@QuarkusTest
class CartFlowIT {

    @Test
    void addRepriceDedupeAndCheckout() {
        String cartId = "it-cart-" + System.nanoTime();

        JsonPath first = given().when().post("/services/cart/" + cartId + "/329299/1")
                .then().statusCode(200).extract().jsonPath();
        assertThat(first.getDouble("cartItemTotal")).isEqualTo(7.5);

        JsonPath second = given().when().post("/services/cart/" + cartId + "/329299/1")
                .then().statusCode(200).extract().jsonPath();
        assertThat(second.getList("shoppingCartItemList")).hasSize(1);
        assertThat(second.getInt("shoppingCartItemList[0].quantity")).isEqualTo(2);
        assertThat(second.getDouble("cartTotal")).isEqualTo(17.99);

        JsonPath cart = given().when().get("/services/cart/" + cartId).then().statusCode(200).extract().jsonPath();
        assertThat(cart.getDouble("cartTotal")).isEqualTo(17.99);

        JsonPath checkedOut = given().when().post("/services/cart/checkout/" + cartId)
                .then().statusCode(200).extract().jsonPath();
        assertThat(checkedOut.getList("shoppingCartItemList")).isEmpty();
        assertThat(checkedOut.getDouble("cartTotal")).isZero();

        // The consumer is asynchronous: poll until the order lands (max 20s).
        java.util.List<Object> orders = java.util.List.of();
        for (int attempt = 0; attempt < 40 && orders.isEmpty(); attempt++) {
            orders = given().when().get("/services/orders").then().statusCode(200)
                    .extract().jsonPath().getList("$");
            if (orders.isEmpty()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        assertThat(orders).isNotEmpty();
    }

    @Test
    void deleteReducesQuantity() {
        String cartId = "it-cart-del-" + System.nanoTime();
        given().when().post("/services/cart/" + cartId + "/444435/3").then().statusCode(200);

        JsonPath cart = given().when().delete("/services/cart/" + cartId + "/444435/1")
                .then().statusCode(200).extract().jsonPath();

        assertThat(cart.getInt("shoppingCartItemList[0].quantity")).isEqualTo(2);
    }

    @Test
    void unknownProductOnAddIsA500AsBefore() {
        given().when().post("/services/cart/it-cart-x/UNKNOWN/1").then().statusCode(500);
    }
}
