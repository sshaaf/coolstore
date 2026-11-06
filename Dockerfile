# ---------------------------------------------------------------------------------------------
# CoolStore monolith - Quarkus 3.27.6 / Java 21 / Jakarta EE 10
#
#   podman build -t coolstore:1.0.0 .
#   podman run --rm -p 8080:8080 \
#     -e DB_URL=jdbc:postgresql://postgres:5432/postgresDB -e DB_USER=postgresUser \
#     -e DB_PASSWORD=... -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
#     -e AUDIT_ENABLED=false coolstore:1.0.0
#
# Two stages, both on Red Hat UBI 9:
#   build   ubi9/openjdk-21          - JDK 21 + Maven 3.9, compiles the Quarkus fast-jar
#   runtime ubi9/openjdk-21-runtime  - JDK 21 only (ubi-micro base), runs as non-root
#
# Nothing environment-specific is baked into any layer: no secret, no URL, no profile and no
# audit endpoint. All of them arrive as environment variables (ConfigMap/Secret on OpenShift).
# ---------------------------------------------------------------------------------------------

ARG BUILDER_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.24
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

# =============================================================================================
# Stage 1 - build
# =============================================================================================
FROM ${BUILDER_IMAGE} AS build

# The UBI OpenJDK builder image already runs as UID 185 / GID 0 and ships Maven 3.9 + JDK 21 with
# a group-writable HOME (/home/default, 0770 root:0). Working inside HOME keeps the build rootless
# and makes it work unchanged under OpenShift's arbitrary-UID build pods.
WORKDIR /home/default
# Create the work directory as the image user (not root) so Maven can write target/ into it.
RUN mkdir -p /home/default/build && chmod g=u /home/default/build
WORKDIR /home/default/build

ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

# The vendored audit-logging-library 2.0.0 is resolved as a normal Maven dependency from the
# project-local file:// repository declared in pom.xml (lib/repo). No system scope, no install
# step, no watermark in the cache: only that repository is copied.
COPY --chown=185:0 pom.xml ./
COPY --chown=185:0 lib/repo ./lib/repo

# -ntp keeps the build log readable; -DskipTests compiles the test sources but never runs them
# (the suite needs H2 only, it is verified by `mvn verify` before the image is built).
COPY --chown=185:0 src ./src
RUN mvn -B -ntp -DskipTests package \
 && test -f target/quarkus-app/quarkus-run.jar

# =============================================================================================
# Stage 2 - runtime
# =============================================================================================
FROM ${RUNTIME_IMAGE} AS runtime

LABEL name="coolstore" \
      version="1.0.0" \
      vendor="Red Hat, Inc." \
      summary="CoolStore monolith - Quarkus 3 / Java 21 / Jakarta EE 10" \
      description="CoolStore storefront and checkout API: JAX-RS, Hibernate ORM, Flyway, Kafka, Keycloak SSO and NDJSON audit over TCP." \
      io.k8s.description="CoolStore monolith on Quarkus 3 (Java 21) serving the AngularJS SPA and the /services REST API." \
      io.k8s.display-name="CoolStore monolith" \
      io.openshift.expose-services="8080:http" \
      io.openshift.tags="java,openjdk21,quarkus,jakarta-ee,coolstore"

# HOME and the JVM temp/home directories point at /tmp: the container root filesystem is mounted
# read-only on OpenShift and /tmp is an emptyDir, so nothing else is ever written.
# JAVA_TOOL_OPTIONS is honoured by every JVM, which keeps the entrypoint a plain `java -jar`
# while still letting operations override the JVM flags through a single variable.
ENV HOME=/tmp \
    LANG=C.UTF-8 \
    QUARKUS_HTTP_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.util.logging.manager=org.jboss.logmanager.LogManager -Djava.io.tmpdir=/tmp -Duser.home=/tmp"

WORKDIR /deployments

# Only the fast-jar layout is shipped; the Maven repository, the sources and the vendored jars
# stay in the builder stage. Files are group-0 readable so an arbitrary UID can run them.
COPY --chown=185:0 target/quarkus-app/lib/ /deployments/lib/
COPY --chown=185:0 target/quarkus-app/app/ /deployments/app/
COPY --chown=185:0 target/quarkus-app/quarkus/ /deployments/quarkus/
COPY --chown=185:0 target/quarkus-app/quarkus-run.jar /deployments/quarkus-run.jar

# UID 185 is the image default. OpenShift's restricted-v2 SCC replaces it with a namespace UID;
# the image stays compatible because everything the process needs is owned by GID 0.
USER 185
EXPOSE 8080

# The runtime image is ubi9-micro based: there is no shell. Keep every command in exec form.
# OpenShift ignores HEALTHCHECK (it uses startup/readiness/liveness probes instead); this is for
# podman/docker users.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["/usr/bin/curl", "-fsS", "http://127.0.0.1:8080/q/health/live"]

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
