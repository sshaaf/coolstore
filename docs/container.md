# Container image (Group 7)

## Build

```bash
mvn -B verify                                   # tests
mvn -B package -Pcontainer -Dquarkus.container-image.build=true   # optional Quarkus build
podman build -t coolstore:1.0.0 .               # the canonical build (Dockerfile at the root)
```

`Dockerfile` is a two-stage build on Red Hat UBI 9:

1. `registry.access.redhat.com/ubi9/openjdk-21:1.24` — JDK 21 + Maven 3.9, runs as UID 185, builds
   in `/home/default/build` (rootless, arbitrary-UID friendly). The vendored
   `audit-logging-library-2.0.0.jar` is resolved as a normal dependency from the project-local
   `file://` repository in `lib/repo` declared in `pom.xml` — no `system` scope, no `install-file`.
   `mvn -B -ntp -DskipTests package` produces `target/quarkus-app/`.
2. `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24` — copies only the fast-jar
   (`lib/`, `app/`, `quarkus/`, `quarkus-run.jar`) to `/deployments`, keeps the image's UID 185,
   sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError …` and
   `ENTRYPOINT ["java","-jar","/deployments/quarkus-run.jar"]`.

Hardening: non-root fixed UID with GID 0 ownership (`COPY --chown=185:0`) so OpenShift's
arbitrary UID works, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` with an
`emptyDir` on `/tmp` (the app writes nowhere else: `HOME`, `user.home` and `java.io.tmpdir` are all
`/tmp`), no capabilities, no service-account token, no shell tooling and no build tools in the
runtime layer, no secret or environment value baked in.

The runtime image is `ubi9-micro` based — **there is no shell**. That is why the entrypoint and the
`HEALTHCHECK` are exec-form, and why the Deployment has no `sh -c` `preStop` hook (shutdown is
SIGTERM + `quarkus.shutdown.timeout=30s` inside a 45 s grace period).

## Environment contract (all runtime, none build-time)

| Variable | Required | Secret | Notes |
|---|---|---|---|
| `DB_URL` | yes (prod) | no | `jdbc:postgresql://postgres:5432/postgresDB` |
| `DB_USER` | yes | no | `postgresUser` |
| `DB_PASSWORD` | yes | **yes** | never in the image |
| `KAFKA_BOOTSTRAP_SERVERS` | yes | no | broker bootstrap |
| `ORDERS_TOPIC` / `ORDERS_CONSUMER_GROUP` | no | no | default `orders` / `coolstore-order-service` |
| `OIDC_AUTH_SERVER_URL` / `OIDC_REALM` / `OIDC_CLIENT_ID` | when SSO on | no | redirect URIs must include the Route host |
| `OIDC_ENABLED` | no | no | default `false` (backend bearer protection off) |
| `OIDC_CLIENT_SECRET` | no | **yes** | only for a confidential OIDC client |
| `AUDIT_ENABLED` / `AUDIT_STREAM_HOST` / `AUDIT_STREAM_PORT` | no | no | no-op logger when disabled |
| `QUARKUS_HTTP_PORT` | no | no | default 8080 |
| `LOG_JSON` / `LOG_LEVEL` | no | no | prod defaults `true` / `INFO` |
| `CART_STORE_MAX_CARTS` / `CART_STORE_IDLE_TIMEOUT` | no | no | cart eviction |

Missing `DB_URL` fails fast with a Quarkus configuration error (no silent default in `%prod`).

## Verification checklist

* `podman run` with the full env set → `/` serves the SPA, `/q/health/ready` is `UP`.
* `podman run` without `DB_URL` → startup fails with a clear configuration error.
* `podman run` → `id -u` is 185 in the image config; on OpenShift the pod runs as the SCC UID and
  still works (nothing outside `/tmp` is written).
* Image scan (Trivy/ACS) + SBOM (`syft coolstore:1.0.0 -o spdx-json`) recorded with the release.
* Size note: `bower_components` dominates the application jar (AngularJS 1.x EOL); pruning is a
  follow-up.

The image was **not** built in this migration environment (no Podman, no JDK/Maven): the build gate
is `podman build -t coolstore:1.0.0 .` on a machine with registry access. See
`CONTAINERIZATION_REPORT.md` for the static verification that was performed instead.
