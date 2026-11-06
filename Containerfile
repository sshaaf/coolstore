# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CoolStore - multi-stage build (Podman / Docker compatible)
#   build stage : ubi9/openjdk-21 (Maven + JDK 21)
#   run stage   : ubi9/openjdk-21-runtime (JRE 21 only, non-root, no build tools)
# ---------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21:1.20 AS build

USER 1001
WORKDIR /home/default/build

# Dependency cache layer: only re-resolved when the POM changes.
# lib/maven-repo is required because the audit library is vendored (no public release).
COPY --chown=1001:1001 pom.xml ./
COPY --chown=1001:1001 lib/maven-repo lib/maven-repo
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY --chown=1001:1001 src src
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20

ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    # Container-aware JVM sizing + fail fast on OOM instead of thrashing
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" \
    # Quarkus runtime configuration defaults (all overridable by ConfigMap/Secret)
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080

COPY --from=build --chown=185:0 /home/default/build/target/quarkus-app /deployments/

EXPOSE 8080
USER 185
WORKDIR /deployments

# Readiness is decided by Kubernetes probes (/q/health/ready), not by this image.
CMD ["java", "-jar", "/deployments/quarkus-run.jar"]
