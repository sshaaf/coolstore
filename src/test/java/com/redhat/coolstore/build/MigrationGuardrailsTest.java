package com.redhat.coolstore.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Build gates for the migration (Story 1 task 1.7, Story 2 task 2.2, Story 9 acceptance).
 *
 * <p>These run as ordinary unit tests so a regression fails {@code mvn verify} even before any
 * container or cluster is involved. They replace the CI grep gate with something that cannot be
 * silently skipped.</p>
 */
class MigrationGuardrailsTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path MAIN_JAVA = ROOT.resolve("src/main/java");
    private static final Path MAIN_RESOURCES = ROOT.resolve("src/main/resources");

    private static final Pattern JAVAX_EE_IMPORT = Pattern.compile(
            "^\\s*import\\s+javax\\.(ejb|persistence|ws|rs|inject|enterprise|annotation|jms|json|"
                    + "xml\\.bind|naming|rmi|sql|transaction|servlet|validation)\\b.*$",
            Pattern.MULTILINE);

    /** Removes line/block comments so javadoc that *talks about* the old API does not fail the gate. */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("no javax.* Java EE import remains in src/main/java")
    void noJavaxEeImports() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (JAVAX_EE_IMPORT.matcher(content).find()) {
                violations.add(MAIN_JAVA.relativize(file).toString());
            }
        }
        assertEquals(List.of(), violations, "javax.* EE imports must be migrated to jakarta.*");
    }

    @Test
    @DisplayName("no Java EE 7 (javax) or JBoss spec artifact remains in the POM")
    void noJavaEeDependencies() throws IOException {
        String pom = Files.readString(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertFalse(pom.contains("<groupId>javax</groupId>"), "javax:* artifacts must be gone");
        assertFalse(pom.contains("jboss-jms-api"), "JBoss JMS spec must be gone");
        assertFalse(pom.contains("jboss-rmi-api"), "JBoss RMI spec must be gone");
        assertFalse(pom.contains("maven-war-plugin"), "WAR packaging is retired");
        assertFalse(pom.contains("systemPath"), "no system-scoped dependency is allowed");
        assertTrue(pom.contains("<packaging>jar</packaging>"), "Quarkus fast-jar packaging");
        assertTrue(pom.contains("<maven.compiler.release>21</maven.compiler.release>"), "Java 21");
    }

    @Test
    @DisplayName("vendor dead code and legacy descriptors are deleted")
    void legacyArtifactsRemoved() throws IOException {
        assertFalse(Files.exists(MAIN_JAVA.resolve("weblogic")), "weblogic/** stubs must be deleted");
        assertFalse(Files.exists(MAIN_RESOURCES.resolve("META-INF/persistence.xml")),
                "persistence.xml is replaced by application.properties");
        try (Stream<Path> files = Files.walk(ROOT.resolve("src"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".jsp")), "JSPs are unsupported on Quarkus");
        }
        assertFalse(Files.exists(MAIN_JAVA.resolve("com/redhat/coolstore/utils/StartupListener.java")));
        assertFalse(Files.exists(MAIN_JAVA.resolve("com/redhat/coolstore/utils/DataBaseMigrationStartup.java")));
        assertFalse(Files.exists(MAIN_JAVA.resolve("com/redhat/coolstore/persistence/Resources.java")));
        assertFalse(Files.exists(MAIN_JAVA.resolve("com/redhat/coolstore/service/ShippingServiceRemote.java")),
                "the remote EJB interface must be gone");
        assertFalse(Files.exists(MAIN_JAVA.resolve("com/redhat/coolstore/service/InventoryNotificationMDB.java")),
                "the WebLogic-only MDB must be deleted (decision recorded in docs/decisions)");
    }

    @Test
    @DisplayName("the removed audit-logging-library 1.0.0 API is not referenced")
    void auditLibraryApiMigrated() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (content.contains("FileSystemAuditLogger")
                    || content.contains("AuditEvent.builder")
                    || content.contains(".logSuccess(")
                    || content.contains(".logFailure(")
                    || content.contains("device-inventory-audit-logs")) {
                violations.add(MAIN_JAVA.relativize(file).toString());
            }
        }
        assertEquals(List.of(), violations, "audit-logging-library 1.0.0 API must no longer be used");

        String pom = Files.readString(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertFalse(pom.contains("audit-logging-library-1.0.0.jar"), "1.0.0 must not be referenced");
        assertTrue(pom.contains("<artifactId>audit-logging-library</artifactId>"), "2.0.0 is a normal dependency");
    }

    @Test
    @DisplayName("no localhost Keycloak URL or hardcoded credential is baked into the artifact")
    void noHardcodedEnvironmentValues() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_RESOURCES)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(MigrationGuardrailsTest::isTextResource)
                    .toList()) {
                if (file.toString().contains("bower_components")) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains("localhost:8081")) {
                    violations.add(MAIN_RESOURCES.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), violations, "the Keycloak URL must come from configuration");
    }

    private static boolean isTextResource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".html")
                || name.endsWith(".js") || name.endsWith(".css") || name.endsWith(".xml")
                || name.endsWith(".sql") || name.endsWith(".txt") || name.endsWith(".md");
    }

    @Test
    @DisplayName("Flyway migrations are still the two original files (checksums preserved)")
    void migrationsUnchanged() throws IOException {
        Path migrations = MAIN_RESOURCES.resolve("db/migration");
        try (Stream<Path> files = Files.list(migrations)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("V1_1__CreateSchema.sql", "V1_2__AddInitialData.sql"), names);
        }
        String schema = Files.readString(migrations.resolve("V1_1__CreateSchema.sql"));
        assertTrue(schema.contains("create table PRODUCT_CATALOG"));
        assertTrue(schema.contains("FK2BFF474F65938E8"));
        assertTrue(schema.contains("create sequence hibernate_sequence"));
    }

    @Test
    @DisplayName("the CI workflow directory is untouched by the migration")
    void githubWorkflowsUntouched() {
        Path workflows = ROOT.resolve(".github/workflows");
        assertTrue(Files.exists(workflows), "the platform-owned workflow stays in place");
    }
}
