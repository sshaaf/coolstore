# Multi-stage, non-root, reproducible image for the CoolStore Quarkus monolith.
#
#   podman build -t coolstore:local .
#   podman run --rm -p 8080:8080 \
#     -e DB_URL=jdbc:postgresql://postgres:5432/postgresDB -e DB_USER=postgresUser \
#     -e DB_PASSWORD=... -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
#     -e AUDIT_ENABLED=false coolstore:local
#
# Build stage: UBI 9 / OpenJDK 21 + Maven. Runtime stage: UBI 9 / OpenJDK 21 runtime as UID 185.
# No secret, no environment-specific value and no audit-log path is baked into any layer.

FROM registry.access.redhat.com/ubi9/openjdk-21:1.20 AS build
USER 0
WORKDIR /build

# The vendored audit-logging-library 2.0.0 is a normal Maven dependency served from the
# project-local repository in lib/repo; it is also installed into the build image's local
# repository so a repository-less build still resolves it.
COPY pom.xml ./
COPY lib/ ./lib/
RUN mvn -B -q org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file \
      -Dfile=lib/audit-logging-library-2.0.0.jar \
      -DgroupId=com.enterprise -DartifactId=audit-logging-library -Dversion=2.0.0 \
      -Dpackaging=jar -DgeneratePom=true

COPY src/ ./src/
RUN mvn -B -DskipTests package

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20 AS runtime
ENV LANG='en_US.UTF-8' \
    LANGUAGE='en_US:en' \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    QUARKUS_HTTP_PORT=8080

WORKDIR /deployments

# Fixed, non-root UID/GID with group 0 (OpenShift arbitrary-UID friendly) and a read-only-friendly
# filesystem: the application only writes to /tmp.
COPY --chown=185:0 target/quarkus-app/lib/ /deployments/lib/
COPY --chown=185:0 target/quarkus-app/*.jar /deployments/
COPY --chown=185:0 target/quarkus-app/app/ /deployments/app/
COPY --chown=185:0 target/quarkus-app/quarkus/ /deployments/quarkus/

USER 185
EXPOSE 8080

# Startup probe is provided by OpenShift (/q/health/started); keep a lightweight container check.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/q/health/live || exit 1

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
