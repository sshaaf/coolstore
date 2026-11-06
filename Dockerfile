# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CoolStore - Quarkus 3.15 fast-jar image for Red Hat OpenShift
#
#   podman build -t coolstore:local -f Dockerfile .
#   (Containerfile is kept byte-identical for `podman build -f Containerfile`,
#    the Makefile and the migration-stage docs.)
#
# Two stages:
#   1. builder  ubi9/openjdk-21          - JDK 21 + Maven, produces target/quarkus-app
#   2. runtime  ubi9/openjdk-21-runtime  - JRE 21 + run-java.sh, non-root, fast-jar
#
# OpenShift notes
#   * Never runs as root: UID 1001, or any arbitrary UID assigned by the
#     namespace range. Every copied path is group-0 writable (--chown=1001:0),
#     which is what makes arbitrary-UID execution work.
#   * No writable path is required at runtime. /tmp is mounted as an emptyDir by
#     the Deployment, so the container can run with a read-only root filesystem.
#   * Configuration is fully externalised (env vars / ConfigMap / Secret); the
#     image contains no environment-specific values and no credentials.
# ---------------------------------------------------------------------------

ARG BUILDER_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.21
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21

############################
# Stage 1: build the fast-jar
############################
FROM ${BUILDER_IMAGE} AS builder

# Maven needs a writable HOME and local repository. /build is created and
# handed to the build user before any COPY so no layer runs as root later on.
USER 0
RUN mkdir -p /build/.m2/repository \
 && chown -R 1001:0 /build \
 && chmod -R g+rwX /build
USER 1001

WORKDIR /build
ENV HOME=/build \
    MAVEN_OPTS="-Dmaven.repo.local=/build/.m2/repository -Xmx1024m"

# Dependency-cache layer: descriptors first, sources last. The warm-up is
# best-effort (the Quarkus extension graph does not always resolve offline);
# the authoritative resolution happens in the package step below.
COPY --chown=1001:0 pom.xml ./
COPY --chown=1001:0 lib/maven-repo ./lib/maven-repo
RUN mvn -B -ntp -Dmaven.test.skip=true dependency:go-offline || true

COPY --chown=1001:0 src ./src

# Tests are executed by CI (`mvn verify`), not inside the image build.
RUN mvn -B -ntp -Dmaven.test.skip=true -Dquarkus.container-image.build=false package \
 && test -f target/quarkus-app/quarkus-run.jar \
 && ls -l target/quarkus-app

############################
# Stage 2: runtime
############################
FROM ${RUNTIME_IMAGE}

# curl-minimal only backs the HEALTHCHECK below; OpenShift ignores HEALTHCHECK
# and uses the liveness/readiness/startup probes declared in the Deployment.
USER 0
RUN microdnf install -y --setopt=install_weak_deps=0 --setopt=tsflags=nodocs curl-minimal \
 && microdnf clean all \
 && rm -rf /var/cache/yum /var/cache/dnf
USER 1001

ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    JAVA_APP_DIR=/deployments \
    JAVA_LIB_DIR=/deployments/lib \
    JAVA_APP_JAR=/deployments/quarkus-run.jar \
    JAVA_OPTIONS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

LABEL org.opencontainers.image.title="coolstore" \
      org.opencontainers.image.description="CoolStore storefront and order backend (Quarkus 3 / Jakarta EE 10)" \
      org.opencontainers.image.vendor="Red Hat" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.openshift.tags="java,quarkus,storefront" \
      io.k8s.description="CoolStore storefront and order backend" \
      io.k8s.display-name="CoolStore"

# The runtime base image already prepares /deployments with group-write
# permissions for arbitrary-UID execution.
WORKDIR /deployments
COPY --from=builder --chown=1001:0 /build/target/quarkus-app/ /deployments/

EXPOSE 8080
USER 1001

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/q/health/live || exit 1

ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]
