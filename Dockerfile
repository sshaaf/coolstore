# syntax=docker/dockerfile:1
# =============================================================================
# CoolStore monolith - Quarkus 3.40 / Jakarta EE 10 / Java 21
#
#   build stage : ubi9/openjdk-21          (JDK 21 + Maven, dependency-cached)
#   run stage   : ubi9/openjdk-21-runtime   (JRE 21 only, non-root 185, no build tools)
#
# Build:   podman build -t coolstore:latest -f Dockerfile .
# Run:     podman run --rm -p 8080:8080 -e DB_URL=... -e REDIS_HOSTS=... coolstore:latest
#
# Everything environment-specific is supplied by OpenShift (ConfigMap/Secret);
# no credentials or hostnames are baked into the image.
# =============================================================================

# Pinned by tag; pin by digest in the production build pipeline for reproducibility.
ARG UBI_JDK_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.20
ARG UBI_JRE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20

# -----------------------------------------------------------------------------
# Stage 1 - build the Quarkus fast-jar
# -----------------------------------------------------------------------------
FROM ${UBI_JDK_IMAGE} AS build

# Root only inside the disposable build stage (needed to lay out /build and to
# chgrp the output for the runtime stage). The runtime image is non-root.
USER 0
WORKDIR /build

# Dependency-cache layer: invalidated only when pom.xml or the vendored repo
# changes. lib/maven-repo is required: com.enterprise:audit-logging-library:2.0.0
# is vendored (no public release) and resolved as a file:// Maven repository.
COPY pom.xml ./
COPY lib/maven-repo ./lib/maven-repo
RUN mvn -B -ntp -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
 && chgrp -R 0 target \
 && chmod -R g+rwX target

# -----------------------------------------------------------------------------
# Stage 2 - runtime (JRE 21, no Maven, no compiler, no sources)
# -----------------------------------------------------------------------------
FROM ${UBI_JRE_IMAGE}

# JAVA_OPTS: container-aware heap sizing, fail fast on OOM, writable tmp only.
# QUARKUS_HTTP_*: bind on all interfaces inside the pod (overridable at runtime).
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080

# Fast-jar layout: quarkus-run.jar + lib/ + app/ + quarkus/.
# chown 185:0 keeps the files readable by OpenShift's arbitrary non-root UID
# (group 0) while staying owned by the image's default runtime user.
COPY --from=build --chown=185:0 /build/target/quarkus-app /deployments/

EXPOSE 8080
USER 185
WORKDIR /deployments

# Image-level smoke check. On OpenShift the Kubernetes probes in deployment.yaml
# (/q/health/started, /q/health/live, /q/health/ready) are authoritative.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/q/health/live || exit 1

# Shell form so JAVA_OPTS can be extended/overridden from a ConfigMap.
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /deployments/quarkus-run.jar"]
