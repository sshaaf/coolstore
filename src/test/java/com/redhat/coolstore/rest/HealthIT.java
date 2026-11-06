package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * SmallRye Health replaces {@code health.jsp}. Readiness includes the database check.
 */
@QuarkusTest
class HealthIT {

    @Test
    void livenessIsUp() {
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void readinessIncludesDatabaseCheck() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'database' }.status", equalTo("UP"));
    }

    @Test
    void metricsEndpointIsExposed() {
        given().when().get("/q/metrics").then().statusCode(200);
    }
}
