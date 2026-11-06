package com.redhat.coolstore.rest;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.http.HttpMethod;

import io.quarkus.runtime.StartupEvent;

/**
 * Serves {@code /coolstore.json} and {@code /keycloak.json} at the exact URLs the AngularJS SPA
 * has always requested, but generated at runtime from configuration instead of being static files
 * baked into the artifact.
 *
 * <p>This removes the last hardcoded environment values from the web layer: the Keycloak
 * {@code auth-server-url} comes from {@code OIDC_AUTH_SERVER_URL}, the realm/client from
 * {@code OIDC_REALM}/{@code OIDC_CLIENT_ID}, and the {@code coolstore.json} switches from
 * {@code SSO_ENABLED}/{@code API_ENDPOINT}. The JSON shapes are unchanged, so {@code app/app.js}
 * and the keycloak JS adapter work without modification.</p>
 */
@ApplicationScoped
public class SpaConfigRoutes {

    @Inject
    Router router;

    @Inject
    ConfigEndpoint configEndpoint;

    void register(@Observes StartupEvent event) {
        router.route(HttpMethod.GET, "/coolstore.json").handler(ctx -> writeJson(ctx, configEndpoint.coolstoreConfig()));
        router.route(HttpMethod.GET, "/keycloak.json").handler(ctx -> writeJson(ctx, configEndpoint.keycloakConfig()));
    }

    private static void writeJson(RoutingContext ctx, Map<String, Object> body) {
        ctx.response()
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .putHeader("Cache-Control", "no-store")
                .end(Json.encode(body));
    }
}
