# CoolStore Monolith — Quarkus 3 / Java 21

The Red Hat CoolStore reference application: an AngularJS storefront, a JAX-RS API under
`/services`, CDI services, JPA/Flyway persistence on PostgreSQL and a reactive-messaging order
pipeline. Formerly deployed as `ROOT.war` on JBoss EAP 7.4; now a Quarkus 3.27 (LTS) application on
Java 21, packaged as a container image and deployed on OpenShift.

See [MIGRATION.md](MIGRATION.md) for what changed and why.

## Prerequisites

* JDK 21 and Maven 3.9+
* PostgreSQL 15+ (database `postgresDB`)
* A Kafka-compatible broker for the `orders` topic (production only; dev/test use the in-memory connector)
* Podman (image build) and `oc` (OpenShift)

## Run locally

```bash
mvn quarkus:dev
# UI:      http://localhost:8080/
# API:     http://localhost:8080/services/products
# Health:  http://localhost:8080/q/health
```

Flyway applies `db/migration` at startup. Set `DB_URL`, `DB_USER`, `DB_PASSWORD` to point at your
database. `SSO_ENABLED=false` and `AUDIT_ENABLED=false` are the `%dev` defaults, so no Keycloak or
audit collector is needed locally.

## Test

```bash
mvn -B verify      # 84 tests: contract, pricing, persistence, messaging, audit, guardrails
```

Tests run on H2 in PostgreSQL mode with the **real** Flyway migrations and the in-memory messaging
connector — no Docker, no broker, no Keycloak.

## Build and deploy

```bash
mvn -B package -DskipTests
podman build -t coolstore:1.0.0 .                 # Dockerfile at the repository root
oc apply -k deploy/overlays/dev                   # or: oc apply -k .  in the target project
```

Database and broker Secrets are created out of band (`secret.yaml` holds placeholders only). See
[CONTAINERIZATION_REPORT.md](CONTAINERIZATION_REPORT.md) for the image build, dependency analysis and
deployment runbook.

Configuration is entirely environment-driven; see [docs/container.md](docs/container.md) for the env
var reference and [docs/openshift.md](docs/openshift.md) for manifests, probes and the cutover/rollback
runbook.

## Keycloak

Import `realm-export.json` (realm `eap`, public client `eap-app`) into Keycloak 20+. Set
`OIDC_AUTH_SERVER_URL`, `OIDC_REALM`, `OIDC_CLIENT_ID`; the SPA fetches `/keycloak.json`, which is
generated at runtime. Redirect URIs must include the Route hostname — no `localhost` value is stored
in the repository. Backend protection of `/services/**` stays opt-in (`OIDC_ENABLED=true`) pending
sign-off.

## Documentation

| Doc | Contents |
|---|---|
| [docs/baseline.md](docs/baseline.md) | behavioural baseline, endpoint inventory, pricing tables, wire contract |
| [docs/data.md](docs/data.md) | datasource, entity mapping, Flyway/checksum rules |
| [docs/audit.md](docs/audit.md) | audit library 1.0.0 → 2.0.0 mapping and configuration |
| [docs/services.md](docs/services.md) | EJB → CDI mapping, messaging channel contract, duplicate handling |
| [docs/cart-state.md](docs/cart-state.md) | cart store model and consistency semantics |
| [docs/web.md](docs/web.md) | SPA layout, JSP removal, OIDC configuration matrix |
| [docs/container.md](docs/container.md) | image build, hardening, env var contract |
| [docs/openshift.md](docs/openshift.md) | manifests, probes, dependencies, cutover/rollback |
| [docs/decisions/](docs/decisions) | recorded decisions (WebLogic dead code, audit emission) |
