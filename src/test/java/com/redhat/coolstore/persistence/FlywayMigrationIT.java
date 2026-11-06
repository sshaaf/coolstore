package com.redhat.coolstore.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Applies the two legacy migration scripts to a pristine PostgreSQL 15 with Flyway 10 (the version
 * Quarkus 3.15 ships) and asserts the seed data the storefront depends on.
 *
 * <p>This is the migration-safety test for the Flyway 4.1.2 -> 10 upgrade: it proves the scripts are
 * still valid, checksummed and idempotent on a fresh schema.
 */
class FlywayMigrationIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("coolstore")
            .withUsername("coolstore")
            .withPassword("coolstore");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    @Test
    @DisplayName("V1_1 and V1_2 apply cleanly and seed 9 products / 9 inventory rows")
    void migrationsApplyAndSeed() throws Exception {
        Flyway flyway = flyway();
        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(2, applied.length, "expected exactly the two legacy migrations");

        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select count(*) from PRODUCT_CATALOG")) {
                assertTrue(rs.next());
                assertEquals(9, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery("select count(*) from INVENTORY")) {
                assertTrue(rs.next());
                assertEquals(9, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery(
                    "select quantity from INVENTORY where itemId = '329299'")) {
                assertTrue(rs.next());
                assertEquals(736, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery("select count(*) from flyway_schema_history")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("Re-running migrate on an already migrated database is a no-op")
    void migrationsAreIdempotent() {
        Flyway flyway = flyway();
        flyway.migrate();
        int before = flyway.info().applied().length;
        flyway.migrate();
        assertEquals(before, flyway.info().applied().length);
    }
}
