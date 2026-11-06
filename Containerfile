# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CoolStore - Quarkus 3 fast-jar image (multi-stage, UBI 9 + OpenJDK 21)
#
#   podman build -t coolstore:local .
#
# The runtime image runs as the OpenShift-compatible arbitrary UID 1001, needs
# no writable path (audit events are streamed over TCP, not written to disk) and
# is built from the Quarkus fast-jar layout, not a WAR.
# ---------------------------------------------------------------------------

############################
# Stage 1: build the fast-jar
############################
FROM registry.access.redhat.com/ubi9/openjdk-21:1.21 AS builder

USER 1001
WORKDIR /build

# Dependency cache layer: copy only the build descriptors first.
COPY --chown=1001:0 pom.xml ./
COPY --chown=1001:0 lib/maven-repo lib/maven-repo

# Resolve the plugin/extension graph once so source edits do not re-download it.
RUN mvn -B -q -DskipTests dependency:go-offline

COPY --chown=1001:0 src src

RUN mvn -B -DskipTests package \
 && ls -l target/quarkus-app

############################
# Stage 2: runtime
############################
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21

ENV LANG='en_US.UTF-8' \
    LANGUAGE='en_US:en' \
    JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    JAVA_APP_DIR=/deployments \
    JAVA_APP_JAR=/deployments/quarkus-run.jar

# OpenShift runs containers with an arbitrary UID in group 0; the runtime base
# image already prepares /deployments with group-write permissions.
WORKDIR /deployments
COPY --from=builder --chown=1001:0 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder --chown=1001:0 /build/target/quarkus-app/*.jar /deployments/
COPY --from=builder --chown=1001:0 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder --chown=1001:0 /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 1001

ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]
