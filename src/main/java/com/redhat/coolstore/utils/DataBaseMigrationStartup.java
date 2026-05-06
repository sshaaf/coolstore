package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.logging.Logger;
import jakarta.inject.Inject;

/**
 * Database migration startup handler
 * Note: Flyway migration is configured via quarkus.flyway.migrate-at-start=true
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    void onStart(@Observes StartupEvent ev) {
        logger.info("Database migration is handled by Quarkus Flyway extension");
    }

}
