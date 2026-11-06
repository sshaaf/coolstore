# CoolStore Monolith

A small e-commerce storefront: product catalog with per-location inventory, a cart with
promotions and calculated shipping, and an asynchronous order pipeline
(`topic/orders` → persist order → deduct inventory), plus audit logging.

**Runtime:** Quarkus 3.40 (Jakarta EE 10) on **Java 21**, packaged as a single container
for Red Hat OpenShift. Previously a Java EE 7 WAR on JBoss EAP 7.4.

```
SPA (AngularJS, static)  ->  /services/{products,cart,orders}  (Quarkus REST, Jackson)
                                     |
                                     +-> services (CDI): Catalog, Product, Promo, Shipping, Cart
                                     |        +-> PostgreSQL (Hibernate ORM, Flyway schema)
                                     |        +-> Redis (cart state, key cart:{cartId})
                                     +-> checkout -> Artemis topic/orders -> OrderMessageConsumer
                                                                  |-> ORDERS + INVENTORY
                                                                  |-> audit stream (TCP/NDJSON, v2)
```

## Prerequisites

* JDK 21 (mandatory: the audit library uses records and virtual threads)
* Maven 3.9+
* For the full stack: PostgreSQL, Redis, ActiveMQ Artemis (or a container runtime for
  Quarkus Dev Services)

No JBoss EAP, WebLogic, or application-server modules are required any more.

## Build and test

```bash
mvn -B package                 # unit tests + fast-jar in target/quarkus-app
mvn -B verify                  # + JaCoCo 80% line-coverage gate
mvn -B verify -Pintegration    # + @QuarkusTest contract/E2E suite (needs containers)
```

The vendored audit library is resolved from `lib/maven-repo` (a file-based Maven
repository). To publish it properly:

```bash
mvn install:install-file -Dfile=lib/audit-logging-library-2.0.0.jar \
  -DgroupId=com.enterprise -DartifactId=audit-logging-library -Dversion=2.0.0 -Dpackaging=jar
```

## Run locally

Fastest path is Quarkus dev mode with Dev Services (starts PostgreSQL/Redis containers
automatically when a container runtime is present):

```bash
mvn quarkus:dev
# SPA:      http://localhost:8080/
# API:      http://localhost:8080/services/products
# Health:   http://localhost:8080/q/health/{live,ready}
# Metrics:  http://localhost:8080/q/metrics
```

Against existing services (no container runtime):

```bash
DB_URL=jdbc:postgresql://localhost:5432/coolstore DB_USERNAME=coolstore DB_PASSWORD=... \
REDIS_HOSTS=redis://localhost:6379 ARTEMIS_URL=tcp://localhost:61616 \
java -jar target/quarkus-app/quarkus-run.jar
```

Flyway creates the schema and seed data (9 products, 9 inventory rows) at startup.
Set `FLYWAY_MIGRATE_AT_START=false` to skip, `OIDC_ENABLED=true` to wire the SPA to
Keycloak (realm `eap`, client `eap-app`; import `realm-export.json`).

## Container image

```bash
podman build -t coolstore:$(git rev-parse --short HEAD) -f Containerfile .
podman run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host:5432/coolstore -e DB_PASSWORD=... \
  -e REDIS_HOSTS=redis://host:6379 -e ARTEMIS_URL=tcp://host:61616 \
  coolstore:latest
```

Multi-stage: `ubi9/openjdk-21` (Maven build) → `ubi9/openjdk-21-runtime` (JRE 21 only),
UID 185, read-only-rootfs compatible, `EXPOSE 8080`, `-XX:MaxRAMPercentage=75`.
Generate an SBOM with `syft coolstore:latest -o spdx-json > coolstore.sbom.json` and scan
with `trivy image --exit-code 1 --severity CRITICAL,HIGH coolstore:latest` in the pipeline.

## Deploy to OpenShift

```bash
oc new-project coolstore
oc create secret generic coolstore-secrets \
  --from-literal=DB_PASSWORD='...' --from-literal=ARTEMIS_PASSWORD='...'
oc apply -k deploy/openshift/base
oc get route coolstore
```

Manifests: Deployment (2+ replicas, startup/liveness/readiness probes), Service, Route (TLS
edge), ConfigMap, Secret template, ServiceAccount, HPA (2→6), PodDisruptionBudget and a
NetworkPolicy limiting ingress to the router and egress to PostgreSQL/Redis/Artemis/Keycloak.
Backing services are expected as `postgresql`, `redis`, `amq-broker`, `keycloak` and the
audit collector; see `docs/runbook.md` for degradation behaviour, DLQ handling and the
≤15 minute rollback.

## Configuration

Every setting is environment-indirected via `${ENV_VAR:default}` in
`src/main/resources/application.properties`. Common ones:

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | localhost/coolstore | PostgreSQL |
| `REDIS_HOSTS`, `CART_TTL_SECONDS` | `redis://localhost:6379`, 86400 | cart store |
| `ARTEMIS_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD` | `tcp://localhost:61616` | broker |
| `ORDERS_TOPIC` | `topic/orders` | JMS destination (contract) |
| `AUDIT_STREAM_ENABLED`, `AUDIT_STREAM_HOST`, `AUDIT_STREAM_PORT` | false / localhost / 5044 | audit v2 stream |
| `OIDC_ENABLED`, `QUARKUS_OIDC_AUTH_SERVER_URL` | false / local Keycloak | SPA SSO |
| `LOG_JSON`, `LOG_LEVEL` | false / INFO | logging |

## Documentation

* `docs/api-parity.md` - endpoint × verb × status × payload matrix
* `docs/decisions.md` - architecture decisions (Redis cart, Artemis, audit v2, …)
* `docs/baseline.md` - legacy baseline and golden fixtures
* `docs/testing.md` - test strategy, coverage, gaps
* `docs/perf-baseline.md` - load profile and performance gate
* `docs/runbook.md` - health, degradation, DLQ, rollback

CI/CD is platform-owned (Tekton on OpenShift); do not add GitHub workflow files.
