# Container image (Group 7)

## Build

```bash
mvn -B verify                                   # tests
mvn -B package -Pcontainer -Dquarkus.container-image.build=true   # optional Quarkus build
podman build -t coolstore:1.0.0 .               # the canonical build
```

`Containerfile` is a two-stage build:

1. `registry.access.redhat.com/ubi9/openjdk-21:1.20` — installs the vendored
   `audit-logging-library-2.0.0.jar` into the build image's local Maven repository
   (`maven-install-plugin:install-file`) and runs `mvn -B -DskipTests package`. No `system` scope and
   no `lib/*.jar` classpath entry is used; `lib/repo` is a normal file-based Maven repository.
2. `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20` — copies `target/quarkus-app/`,
   runs as **UID 185** (group 0), `JAVA_OPTS_APPEND=-XX:MaxRAMPercentage=75`,
   `ENTRYPOINT ["java","-jar","/deployments/quarkus-run.jar"]`.

Hardening: non-root fixed UID, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` with
an `emptyDir` on `/tmp` and `/deployments` mounted read-only (the app writes nothing else), no shell
tooling added to the runtime layer, no secret or environment value baked in.

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
| `AUDIT_ENABLED` / `AUDIT_STREAM_HOST` / `AUDIT_STREAM_PORT` | no | no | no-op logger when disabled |
| `QUARKUS_HTTP_PORT` | no | no | default 8080 |
| `LOG_JSON` / `LOG_LEVEL` | no | no | prod defaults `true` / `INFO` |
| `CART_STORE_MAX_CARTS` / `CART_STORE_IDLE_TIMEOUT` | no | no | cart eviction |

Missing `DB_URL` fails fast with a Quarkus configuration error (no silent default in `%prod`).

## Verification checklist

* `podman run` with the full env set → `/` serves the SPA, `/q/health/ready` is `UP`.
* `podman run` without `DB_URL` → startup fails with a clear configuration error.
* `ps -o uid` inside the container → 185; writes outside `/tmp` fail.
* Image scan (Trivy/ACS) + SBOM (`syft coolstore:1.0.0 -o spdx-json`) recorded with the release.
* Size note: `bower_components` dominates the layer (AngularJS 1.x EOL); pruning is a follow-up.
