package com.redhat.coolstore.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Serves {@code /coolstore.json} and {@code /keycloak.json} from configuration instead of static files.
 *
 * <p>The AngularJS SPA fetches both files at boot. The monolith shipped them as constants, which baked
 * {@code localhost:8081} into the image. They are now rendered per request from MicroProfile Config so the
 * deployment can point the SPA at any Keycloak instance and toggle SSO without rebuilding the image.
 *
 * <p>The response bodies keep the exact legacy shape/casing ({@code MONOLITH}, {@code API_ENDPOINT},
 * {@code SECURE_API_ENDPOINT}, {@code SSO_ENABLED}, {@code auth-server-url}, {@code ssl-required},
 * {@code public-client}, {@code confidential-port}).
 */
@ApplicationScoped
public class StorefrontConfigRoutes {

    private static final Logger LOG = Logger.getLogger(StorefrontConfigRoutes.class);

    @ConfigProperty(name = "coolstore.monolith", defaultValue = "true")
    boolean monolith;

    @ConfigProperty(name = "coolstore.api-endpoint", defaultValue = "")
    String apiEndpoint;

    @ConfigProperty(name = "coolstore.secure-api-endpoint", defaultValue = "")
    String secureApiEndpoint;

    @ConfigProperty(name = "coolstore.sso-enabled", defaultValue = "true")
    boolean ssoEnabled;

    @ConfigProperty(name = "keycloak.realm", defaultValue = "eap")
    String keycloakRealm;

    @ConfigProperty(name = "keycloak.auth-server-url", defaultValue = "http://localhost:8081/")
    String keycloakAuthServerUrl;

    @ConfigProperty(name = "keycloak.resource", defaultValue = "eap-app")
    String keycloakResource;

    @ConfigProperty(name = "keycloak.ssl-required", defaultValue = "external")
    String keycloakSslRequired;

    @ConfigProperty(name = "keycloak.public-client", defaultValue = "true")
    boolean keycloakPublicClient;

    @ConfigProperty(name = "keycloak.confidential-port", defaultValue = "0")
    int keycloakConfidentialPort;

    void init(@Observes Router router) {
        router.route(HttpMethod.GET, "/coolstore.json").handler(this::coolstoreJson);
        router.route(HttpMethod.GET, "/keycloak.json").handler(this::keycloakJson);
        LOG.info("Storefront configuration endpoints registered (/coolstore.json, /keycloak.json)");
    }

    void coolstoreJson(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(coolstoreJson());
    }

    void keycloakJson(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(keycloakJson());
    }

    String coolstoreJson() {
        return "{\n"
                + "  \"MONOLITH\": " + monolith + ",\n"
                + "  \"API_ENDPOINT\": \"" + apiEndpoint + "\",\n"
                + "  \"SECURE_API_ENDPOINT\": \"" + secureApiEndpoint + "\",\n"
                + "  \"SSO_ENABLED\": " + ssoEnabled + "\n"
                + "}";
    }

    String keycloakJson() {
        return "{\n"
                + "    \"realm\": \"" + keycloakRealm + "\",\n"
                + "    \"auth-server-url\": \"" + keycloakAuthServerUrl + "\",\n"
                + "    \"ssl-required\": \"" + keycloakSslRequired + "\",\n"
                + "    \"resource\": \"" + keycloakResource + "\",\n"
                + "    \"public-client\": " + keycloakPublicClient + ",\n"
                + "    \"confidential-port\": " + keycloakConfidentialPort + "\n"
                + "  }";
    }
}
