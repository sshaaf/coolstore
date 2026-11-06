package com.redhat.coolstore.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The AngularJS SPA is served from {@code META-INF/resources}; these are the exact URLs it requests at
 * boot (absolute paths, so the directory layout must be preserved).
 */
@QuarkusTest
class StorefrontIT {

    @Test
    void indexIsServed() {
        given().when().get("/index.html")
                .then().statusCode(200)
                .body(containsString("/app/app.js"))
                .body(containsString("/bower_components/angular/angular.min.js"));
    }

    @Test
    void staticAssetsAreServed() {
        given().when().get("/app/app.js").then().statusCode(200);
        given().when().get("/app/services/cart.js").then().statusCode(200);
        given().when().get("/partials/home.html").then().statusCode(200);
        given().when().get("/bower_components/angular/angular.min.js").then().statusCode(200);
        given().when().get("/app/css/coolstore.css").then().statusCode(200);
    }

    @Test
    @DisplayName("coolstore.json is generated from configuration and keeps MONOLITH=true")
    void coolstoreJsonIsServed() {
        given().when().get("/coolstore.json")
                .then().statusCode(200)
                .body("MONOLITH", equalTo(true))
                .body("SSO_ENABLED", equalTo(false)) // %test.coolstore.sso-enabled=false
                .body("API_ENDPOINT", equalTo(""));
    }

    @Test
    @DisplayName("keycloak.json is generated from configuration, no localhost baked into the image")
    void keycloakJsonIsServed() {
        given().when().get("/keycloak.json")
                .then().statusCode(200)
                .body("realm", equalTo("eap"))
                .body("resource", equalTo("eap-app"))
                .body("'auth-server-url'", containsString("http"));
    }
}
