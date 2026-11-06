package com.redhat.coolstore.rest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Serves the two SPA bootstrap documents that used to be static files
 * ({@code src/main/webapp/coolstore.json} and {@code src/main/webapp/keycloak.json}) and therefore
 * baked environment values into the artifact.
 *
 * <p>Both documents are now generated from configuration, so one image can run in dev, staging and
 * production without a rebuild and no {@code localhost} Keycloak URL is stored in the repository.
 * The JSON shapes are unchanged, so {@code app/services/*.js} and the keycloak JS adapter keep
 * working.</p>
 */
@ApplicationScoped
@Path("/config")
public class ConfigEndpoint {

    @ConfigProperty(name = "coolstore.monolith", defaultValue = "true")
    boolean monolith;

    @ConfigProperty(name = "coolstore.api-endpoint")
    Optional<String> apiEndpoint;

    @ConfigProperty(name = "coolstore.secure-api-endpoint")
    Optional<String> secureApiEndpoint;

    @ConfigProperty(name = "coolstore.sso-enabled", defaultValue = "true")
    boolean ssoEnabled;

    @ConfigProperty(name = "oidc.realm", defaultValue = "eap")
    String oidcRealm;

    @ConfigProperty(name = "oidc.auth-server-url")
    Optional<String> oidcAuthServerUrl;

    @ConfigProperty(name = "oidc.client-id", defaultValue = "eap-app")
    String oidcClientId;

    @ConfigProperty(name = "oidc.ssl-required", defaultValue = "external")
    String oidcSslRequired;

    @ConfigProperty(name = "oidc.public-client", defaultValue = "true")
    boolean oidcPublicClient;

    @GET
    @Path("/coolstore.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> coolstoreConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("MONOLITH", monolith);
        config.put("API_ENDPOINT", apiEndpoint.orElse(""));
        config.put("SECURE_API_ENDPOINT", secureApiEndpoint.orElse(""));
        config.put("SSO_ENABLED", ssoEnabled);
        return config;
    }

    @GET
    @Path("/keycloak.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> keycloakConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("realm", oidcRealm);
        config.put("auth-server-url", oidcAuthServerUrl.orElse(""));
        config.put("ssl-required", oidcSslRequired);
        config.put("resource", oidcClientId);
        config.put("public-client", oidcPublicClient);
        config.put("confidential-port", 0);
        return config;
    }
}
