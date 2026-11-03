# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CoolStore monolith - Quarkus fast-jar on Red Hat UBI 9
#
# Multi-stage build:
#   stage 1 (ubi9/openjdk-21, ships Maven) resolves dependencies and packages
#   stage 2 (ubi9/openjdk-21-runtime) runs the fast-jar as non-root UID 185
#
# Build:  podman build -t quay.io/example/coolstore-monolith:1.0.0 .
# Run:    podman run -p 8080:8080 \
#           -e DB_URL=jdbc:postgresql://host:5432/postgresDB \
#           -e DB_USER=postgresUser -e DB_PASSWORD=postgresPW \
#           -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
#           quay.io/example/coolstore-monolith:1.0.0
# ---------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21:1.21 AS builder

USER 185
WORKDIR /build

# Dependency layer: only re-resolved when the POM (or the vendored jar) changes.
COPY --chown=185:185 pom.xml ./
COPY --chown=185:185 lib/ ./lib/
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY --chown=185:185 src/ ./src/
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' \
    JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    JAVA_MAX_MEM_RATIO="80" \
    JAVA_OPTIONS=""

WORKDIR /deployments

COPY --from=builder --chown=185:185 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder --chown=185:185 /build/target/quarkus-app/*.jar /deployments/
COPY --from=builder --chown=185:185 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder --chown=185:185 /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
