package com.redhat.coolstore.it;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Boot + static-asset smoke test. Requires the full stack (Dev Services PostgreSQL/Redis,
 * or explicit datasource and Redis configuration): run with {@code mvn verify -Pintegration}.
 */
@QuarkusTest
class SmokeIT {

    @Test
    void livenessIsUp() {
        given().when().get("/q/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void storefrontIsServedFromMetaInfResources() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("/bower_components/angular/angular.min.js"))
                .body(containsString("/app/services/cart.js"));
        given().when().get("/bower_components/jquery/dist/jquery.min.js").then().statusCode(200);
        given().when().get("/coolstore.json").then().statusCode(200);
    }

    @Test
    void metricsAreExposed() {
        given().when().get("/q/metrics").then().statusCode(200);
    }
}
