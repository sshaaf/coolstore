package com.redhat.coolstore.utils;

import java.sql.Connection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.agroal.api.AgroalDataSource;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Readiness check replacing the WebLogic {@code health.jsp} page (which just returned {@code 1}).
 *
 * <p>Readiness must fail when the datasource is unreachable so a pod that cannot serve traffic is taken
 * out of the route. Liveness deliberately stays dependency-free (provided by SmallRye Health).
 */
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(DatabaseHealthCheck.class);

    @Inject
    AgroalDataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("database");
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return builder.status(valid).withData("database", "PostgreSQL").build();
        } catch (Exception e) {
            LOG.errorf(e, "Readiness check failed: datasource unreachable");
            return builder.down().withData("error", e.getClass().getSimpleName()).build();
        }
    }
}
