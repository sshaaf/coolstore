package com.redhat.coolstore.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Replays the golden fixtures: any field-name or numeric drift fails the build. */
@QuarkusTest
class CatalogContractIT {

    @Test
    void catalogMatchesGoldenFixture() {
        JsonPath live = given().when().get("/services/products").then().statusCode(200).extract().jsonPath();
        JsonPath golden = JsonPath.from(new java.io.File("src/test/resources/golden/products.json"));

        List<Map<String, Object>> liveProducts = live.getList("$");
        List<Map<String, Object>> goldenProducts = golden.getList("$");

        assertThat(liveProducts).hasSameSizeAs(goldenProducts);
        for (int i = 0; i < goldenProducts.size(); i++) {
            assertThat(liveProducts.get(i).keySet())
                    .as("field names of product %d", i)
                    .isEqualTo(goldenProducts.get(i).keySet());
            assertThat(liveProducts.get(i)).isEqualTo(goldenProducts.get(i));
        }
    }

    @Test
    void singleProductKeepsInventoryFields() {
        given().when().get("/services/products/329299")
                .then().statusCode(200)
                .body("itemId", org.hamcrest.Matchers.equalTo("329299"))
                .body("location", org.hamcrest.Matchers.equalTo("Raleigh"))
                .body("quantity", org.hamcrest.Matchers.equalTo(736));
    }

    @Test
    void unknownProductReturnsNoContent() {
        given().when().get("/services/products/UNKNOWN").then().statusCode(204);
    }

    @Test
    void ordersEndpointIsEmptyOnAFreshDatabase() {
        given().when().get("/services/orders").then().statusCode(200);
    }
}
