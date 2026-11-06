package com.redhat.coolstore.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Build-time guard: the migration is only complete when the source tree contains no Java EE 7
 * {@code javax.*} imports and no WebLogic/JBoss vendor APIs. This replaces the manual
 * {@code grep} check from the plan so a regression fails the build.
 */
class StaticGuardsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static List<Path> javaSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "run from the module root: " + SOURCES.toAbsolutePath());
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("No javax.* imports remain in src/main/java")
    void noJavaxImports() throws IOException {
        List<String> violations = violations("import javax.");
        assertTrue(violations.isEmpty(), "javax.* imports found: " + violations);
    }

    @Test
    @DisplayName("No WebLogic packages or imports remain")
    void noWeblogicReferences() throws IOException {
        assertTrue(violations("weblogic").isEmpty(), "weblogic references found: " + violations("weblogic"));
    }

    @Test
    @DisplayName("No JBoss JNDI names or remote-EJB lookups remain")
    void noJndiLookups() throws IOException {
        assertTrue(violations("java:jboss/").isEmpty(), "JBoss datasource JNDI names found");
        assertTrue(violations("java:/topic/").isEmpty(), "JMS JNDI topic names found");
        assertTrue(violations("InitialContext").isEmpty(), "JNDI InitialContext usage found");
        assertTrue(violations("PortableRemoteObject").isEmpty(), "RMI PortableRemoteObject usage found");
    }

    @Test
    @DisplayName("The legacy EJB/WebLogic source packages are gone")
    void legacyPackagesDeleted() {
        assertFalse(Files.exists(Path.of("src/main/java/weblogic")));
        assertFalse(Files.exists(Path.of("src/main/java/com/redhat/coolstore/utils/StartupListener.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/redhat/coolstore/persistence/Resources.java")));
        assertFalse(Files.exists(Path.of("src/main/resources/META-INF/persistence.xml")));
        assertFalse(Files.exists(Path.of("src/main/webapp")));
    }

    private static List<String> violations(String needle) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(needle);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
    }
}
