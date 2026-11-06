# Containerization Report: CoolStore Monolith

**Application:** CoolStore storefront (catalog, cart, checkout, orders, audit)
**Runtime:** Quarkus 3.40.1 · Jakarta EE 10 · Java 21 · packaged as a Quarkus fast-jar
**Target platform:** Red Hat OpenShift (image built on Red Hat UBI 9)
**Prepared:** 2026-11-06 · MigIQ containerize stage

---

## 1. Executive summary

The migrated application is a single Spring-free Quarkus service that serves an AngularJS SPA
and a `/services/*` REST API from the same JVM. It is containerized as **one deployable unit**
(one image, one Deployment) because the graph shows a single cohesive business core
(`rest` → `service` → `model`) plus statically served SPA assets; there is no module boundary
that justifies splitting into separate services at this stage.

| Item | Value |
|---|---|
| Base images | `registry.access.redhat.com/ubi9/openjdk-21:1.20` (build) → `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20` (run) |
| Build type | Multi-stage Maven build → Quarkus fast-jar (`quarkus-run.jar`) |
| Runtime user | `185` (image default, group `0` for OpenShift arbitrary UID) |
| HTTP port | `8080` |
| Health endpoints | `/q/health/started`, `/q/health/live`, `/q/health/ready`, `/q/metrics` |
| External services | PostgreSQL 15, Redis 7, AMQ 7/Artemis (JMS `topic/orders`); Keycloak and an audit TCP collector are optional |
| Security posture | Non-root, read-only root filesystem, all capabilities dropped, `seccompProfile: RuntimeDefault`, no secrets or hostnames baked into the image, TLS edge Route, NetworkPolicy |
| Verification in this environment | `oc kustomize .` renders 14 objects successfully. **Image build and cluster smoke test were not possible** (no `podman`/`buildah`, no JDK 21/Maven, no cluster in the containerizer sandbox) — see §9. |

---

## 2. Graph analysis findings (`rgctl`)

Index command (mig-containerize profile):

```bash
rgctl discover . --with-cfg --with-security --with-taint --with-harmonic
```

| Metric | Value |
|---|---|
| Files indexed | 1,315 (3 skipped on parse errors) |
| Nodes / edges | 37,183 nodes / 129,149 edges (snapshot reports 107,795 deduplicated edges) |
| Functions analysed | 26,972 (CFG/PDG built for 26,877) |
| Communities | 24,819 detected (overlay: 25,351 communities, 37,183 assignments), modularity 0.47 |
| Circular dependencies | 330 |
| Potential secrets | 3 findings |
| Taint flows | 1 flow, flagged vulnerable |
| Java symbols | 926 across 27 main source files (plus 17 test files) |
| JavaScript symbols | 29,427 — almost entirely vendored `src/main/resources/META-INF/resources/bower_components/**` |

### 2.1 Structure

```
SPA (AngularJS, static META-INF/resources)
  └── /services/{products,cart,orders}   (Quarkus REST + Jackson)
        ├── CatalogService / ProductService / PromoService / ShippingService
        ├── ShoppingCartService → RedisCartStore      → Redis
        ├── OrderService       → PostgreSQL (Hibernate ORM, Flyway)
        └── checkout → JMS topic/orders → OrderMessageConsumer → ORDERS/INVENTORY
                                                   └── AuditPublisher → TCP/NDJSON collector
```

**Important caveat about the graph:** the repository vendors the complete AngularJS/PatternFly
`bower_components` tree: 29,427 JavaScript symbols dominate the 37,183-node graph, and most
PageRank hotspots (`Date`, jQuery/Bootstrap/Lodash helpers) are third-party browser code. The
top PageRank list is therefore not a meaningful list of application god-nodes. The Java call
graph, POM and configuration were used for the container design instead.

Communities are similarly dominated by tiny JS clusters (tens of thousands of
single-symbol communities, modularity 0.47); they do **not** suggest a microservice split.
The Java packages form four cohesive layers within one deployable:

| Package | Responsibility | Container boundary |
|---|---|---|
| `com.redhat.coolstore.rest` | JAX-RS endpoints, error mappers | same image |
| `com.redhat.coolstore.service` | catalog, cart, order, promo, shipping | same image |
| `com.redhat.coolstore.model` | JPA entities / DTOs | same image |
| `com.redhat.coolstore.audit` | audit v2 adapter (TCP stream) | same image |
| `com.redhat.coolstore.utils` | producers, wire transformers | same image |

### 2.2 External dependencies found (graph + POM + config)

| Dependency | Evidence | Handling |
|---|---|---|
| PostgreSQL 15 | `jakarta.persistence.EntityManager`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `DB_URL=jdbc:postgresql://…/coolstore` | `postgresql.yaml` (Service + StatefulSet + 10 Gi PVC); Flyway migrates at startup |
| Redis 7 | `quarkus-redis-client`, `REDIS_HOSTS=redis://redis:6379`, `cart:{cartId}` keys | `redis.yaml` (Service + Deployment, ephemeral cache) |
| AMQ 7 / ActiveMQ Artemis | `jakarta.jms.ConnectionFactory/Topic`, `quarkus-artemis-jms` 3.15.0, `ARTEMIS_URL=tcp://amq-broker:61616`, `topic/orders` | `artemis.yaml` (reference broker); production: AMQ Broker Operator / managed broker |
| Keycloak (OIDC) | `quarkus-oidc`, `QUARKUS_OIDC_*`, SPA `keycloak.json`, `realm-export.json` | External/optional; tenant disabled by default (`OIDC_ENABLED=false`) |
| Audit collector (TCP/NDJSON) | `StreamableAuditLogger`, `audit.stream.*` | External/optional; disabled by default (`AUDIT_STREAM_ENABLED=false`), emission is non-blocking |
| Prometheus | `quarkus-micrometer-registry-prometheus`, `/q/metrics` | Scraped via pod annotations + monitoring NetworkPolicy ingress |
| Internal Maven artifact | `com.enterprise:audit-logging-library:2.0.0` from `lib/maven-repo` (file-based repo) | Copied into the build stage (`COPY lib/maven-repo`) |

### 2.3 Containerization blockers and how they were resolved

| # | Finding (source) | Severity | Resolution |
|---|---|---|---|
| 1 | **Database is mandatory at startup** — Flyway `migrate-at-start=true`, datasource health check; without PostgreSQL the pod never becomes ready (`application.properties`, `docs/runbook.md`) | High | PostgreSQL Service/StatefulSet shipped; `DB_URL` points at `postgresql:5432`; startupProbe allows 200 s for JVM + Flyway; managed DB supported by changing one ConfigMap key |
| 2 | **Cart state is externalized to Redis** — no in-JVM session (`quarkus-redis-client`); Redis outage → `CartStoreException` → HTTP 500 for cart paths | High | Redis Service/Deployment shipped; readiness includes Redis; cart data is TTL-bound and intentionally ephemeral |
| 3 | **JMS consumer + checkout require Artemis** — `ConnectionFactory`, `topic/orders`; readiness includes Artemis | High | Reference Artemis Deployment shipped; production must use the AMQ Broker Operator/managed broker with `max-delivery-attempts=5` and DLA `orders.dlq`; `ARTEMIS_HEALTH_EXCLUDE=true` supports catalog-only deploys |
| 4 | **Hardcoded `localhost` defaults** — `DB_URL`, `REDIS_HOSTS`, `ARTEMIS_URL`, `audit.stream.host`, OIDC URL all default to localhost (`application.properties`, `AuditPublisher.java:58`) | Medium | Every value is `${ENV:default}`; the ConfigMap sets cluster DNS names. No source change needed, but the defaults are unsafe if the ConfigMap is not applied |
| 5 | **Default database credentials** `coolstore/coolstore` in `application.properties` | High (secret) | Overridden from `coolstore-secrets`; `DB_PASSWORD` is required and has no default in the manifests |
| 6 | **SPA runtime config must be overridable** — `app.js` fetches `coolstore.json`/`keycloak.json` at runtime | Medium | Both files are ConfigMap keys mounted at `/deployments/config`, and `QUARKUS_HTTP_STATIC_LOCATIONS=/deployments/config,classpath:META-INF/resources` makes the mount win over the image copy |
| 7 | **Java 21 is mandatory** — audit library uses records/virtual threads; `maven.compiler.release=21` | High | UBI 9 JDK 21 builder and JRE 21 runtime images |
| 8 | **Vendored Maven artifact** — the audit library has no public release; POM uses `file://${project.basedir}/lib/maven-repo` | High | Build stage copies `pom.xml` + `lib/maven-repo` before `src`; `.dockerignore` must never exclude `lib/` |
| 9 | **OpenShift arbitrary UID + read-only rootfs** — UBI images run as 185; restricted-v2 assigns a namespace UID | Medium | Image files copied `--chown=185:0`; no `runAsUser` pinned; writable `/tmp` emptyDir; `-Djava.io.tmpdir=/tmp`; `readOnlyRootFilesystem: true` |
| 10 | **No local filesystem or process execution in app code** — grep for `Files.*`, `FileOutputStream`, `Runtime.exec`, `ProcessBuilder` returns nothing; audit logs go to TCP, not to disk | Info (no blocker) | Root filesystem can be read-only; no PVC needed for uploads/logs |
| 11 | **330 circular dependencies** — concentrated in vendored browser JavaScript; negligible for the JVM build | Low | No action; the Quarkus build is acyclic at the module level |
| 12 | **3 potential secrets** — default DB credentials in `application.properties`, empty Artemis password default, and the committed Keycloak realm export (7 clients, no users) | Medium | Externalized into `secret.yaml` (placeholders) / OpenShift Secret; no credentials in the image |

Taint note: the single taint flow reported by `--with-taint` is a framework-level data flow
(HTTP → JPA/JMS). All database access uses JPA Criteria queries (no string-concatenated SQL)
and no shell/filesystem sink exists in application code, so it does not change the container
design; it remains on the security backlog for review.

---

## 3. Containerization strategy

### 3.1 Base image selection

| Stage | Image | Why |
|---|---|---|
| Build | `registry.access.redhat.com/ubi9/openjdk-21:1.20` | Contains JDK 21 + Maven 3.9 and matches the mandatory Java 21 target; Maven Central reachable from CI |
| Runtime | `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20` | JRE 21 only, no compiler/Maven, smaller attack surface, default non-root user 185 |

Alternatives rejected: `ubi9/ubi-minimal` + custom JRE (more maintenance), `openjdk-17` (cannot
run the Java 21 audit library), full `ubi9/openjdk-21` at runtime (ships a compiler).
Tags are pinned to UBI stream `1.20`; pin by digest in the production pipeline.

### 3.2 Build strategy

* **Multi-stage:** `mvn -B -ntp -DskipTests package` produces `target/quarkus-app`
  (`quarkus-run.jar` + `lib/` + `app/` + `quarkus/`); only that directory is copied.
* **Dependency cache:** `pom.xml` + `lib/maven-repo` are copied first and
  `dependency:go-offline` runs in its own layer, so source changes do not re-download Maven
  dependencies.
* **Reproducibility:** build-time only; runtime configuration is entirely environment-driven.
* **Build context:** the root `pom.xml`, `lib/`, `src/` only — `.dockerignore` (and the
  podman-native `.containerignore`) exclude `.git`, `.rgctl`, docs, workspaces and `target`.

### 3.3 Security hardening applied

- ✅ Multi-stage build; no build tools, sources or test artifacts in the runtime image
- ✅ Non-root execution (`USER 185`), group `0` ownership for OpenShift arbitrary UIDs
- ✅ `readOnlyRootFilesystem: true`; only `/tmp` (emptyDir) is writable
- ✅ `allowPrivilegeEscalation: false`, all Linux capabilities dropped
- ✅ `seccompProfile: RuntimeDefault` at pod level
- ✅ No secrets, passwords or environment hostnames baked into the image
- ✅ Resource requests/limits on every container
- ✅ Startup, liveness and readiness probes (Quarkus SmallRye Health)
- ✅ TLS edge Route with HTTP→HTTPS redirect
- ✅ NetworkPolicy: ingress only from the OpenShift router/monitoring; egress only to
  PostgreSQL, Redis, Artemis, optional Keycloak/audit collector, and DNS
- ✅ PodDisruptionBudget (`minAvailable: 1`) and HPA (2→6 replicas) for availability
- ⚠️ Base-image CVE scan (grype/trivy) and SBOM generation are pipeline steps; they could not
  run in this sandbox (see §9)

---

## 4. Generated artifacts (all at repo root)

| File | Contents |
|---|---|
| `Dockerfile` | Multi-stage UBI 9 JDK 21 → JRE 21 build, non-root, health check |
| `.dockerignore` | Build-context exclusions (Docker; mirrors `.containerignore` for Podman) |
| `deployment.yaml` | Deployment: 2 replicas, 3 probes, resources, hardened security context, ConfigMap/Secret wiring, `/tmp` and SPA-config volumes |
| `service.yaml` | ClusterIP Service on port 8080 |
| `route.yaml` | OpenShift Route, edge TLS, redirect |
| `configmap.yaml` | All non-secret runtime configuration + `coolstore.json` / `keycloak.json` |
| `secret.yaml` | Template for DB/Artemis/Redis credentials (placeholders only; excluded from kustomization) |
| `serviceaccount.yaml` | ServiceAccount with token automount disabled |
| `postgresql.yaml` | PostgreSQL 15 Service + StatefulSet + 10 Gi PVC |
| `redis.yaml` | Redis 7 Service + Deployment (ephemeral cart cache, optional AUTH) |
| `artemis.yaml` | Reference AMQ 7/Artemis broker Service + Deployment (`topic/orders`) |
| `hpa.yaml` | HorizontalPodAutoscaler, CPU 70 %, 2→6 replicas |
| `pdb.yaml` | PodDisruptionBudget `minAvailable: 1` |
| `networkpolicy.yaml` | Ingress from router/monitoring; egress to dependencies + DNS |
| `kustomization.yaml` | Applies the whole set; rewrites the image to the internal registry |
| `CONTAINERIZATION_REPORT.md` | This document |

The execute-stage `Containerfile` and `deploy/openshift/base/` duplicates were removed so
there is exactly one deployable definition; `README.md` and `docs/runbook.md` were updated to
match.

---

## 5. Deployment instructions

### 5.1 Prerequisites

1. OpenShift 4.x cluster with `oc` logged in (project-admin rights).
2. Access to `registry.access.redhat.com` and `registry.redhat.io` (pull secret on the cluster).
3. Maven Central (or an internal mirror) reachable from the build.
4. Optional: Keycloak (realm `eap`, client `eap-app`) and an audit TCP collector.

### 5.2 Create the project and the secret

```bash
oc new-project coolstore

# Preferred: create the Secret out-of-band (do not commit real credentials)
oc create secret generic coolstore-secrets \
  --from-literal=DB_USERNAME=coolstore \
  --from-literal=DB_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=ARTEMIS_USER=amq \
  --from-literal=ARTEMIS_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=REDIS_PASSWORD=""
```

The `secret.yaml` shipped in Git is a **template only** and is intentionally excluded from
`kustomization.yaml`, so applying the kustomization can never overwrite credentials that
already exist. Create the Secret before deploying (the Deployment fails fast with
`CreateContainerConfigError` if it is missing).

### 5.3 Build and publish the image

Option A — local build with Podman:

```bash
podman build -t coolstore:latest -f Dockerfile .
podman login -u "$(oc whoami)" -p "$(oc whoami -t)" \
  image-registry.openshift-image-registry.svc:5000
podman push coolstore:latest \
  image-registry.openshift-image-registry.svc:5000/coolstore/coolstore:latest
```

Option B — in-cluster binary build (no local toolchain):

```bash
oc new-build --name coolstore --binary --strategy docker --dockerfile - < Dockerfile
oc start-build coolstore --from-dir=. --follow
```

### 5.4 Deploy

```bash
# Build the objects from the root kustomization (namespace coolstore)
oc apply -k .

# Wait for the database and broker first, then the app
oc rollout status statefulset/postgresql
oc rollout status deployment/amq-broker
oc rollout status deployment/redis
oc rollout status deployment/coolstore
```

`oc apply -f .` is **not** supported: the repo root also contains `pom.xml`,
`realm-export.json`, etc.

### 5.5 Verify

```bash
oc get pods,svc,route
ROUTE=$(oc get route coolstore -o jsonpath='{.spec.host}')

curl -fsS "https://${ROUTE}/q/health/ready"
curl -fsS "https://${ROUTE}/services/products" | head
curl -fsS "https://${ROUTE}/" | head          # SPA
curl -fsS "https://${ROUTE}/coolstore.json"   # ConfigMap-mounted SPA config
curl -fsS "https://${ROUTE}/q/metrics" | head # Prometheus

# cart + checkout path (exercises Redis, PostgreSQL and Artemis/topic/orders)
curl -fsS -X POST "https://${ROUTE}/services/cart/smoke-1/329299/2"
curl -fsS -X POST "https://${ROUTE}/services/cart/checkout/smoke-1"
```

Expected: `/q/health/ready` returns `UP` only when PostgreSQL, Redis and Artemis are reachable
(see `docs/runbook.md` for the degradation matrix).

---

## 6. Configuration reference

All values come from `configmap.yaml` (non-secret) or `coolstore-secrets` (secret); none are
baked into the image.

| Variable | Default in manifests | Source | Purpose |
|---|---|---|---|
| `HTTP_PORT` | `8080` | ConfigMap | Quarkus HTTP port |
| `QUARKUS_HTTP_STATIC_LOCATIONS` | `/deployments/config,classpath:META-INF/resources` | ConfigMap | Makes ConfigMap SPA config override the image copy |
| `DB_URL` | `jdbc:postgresql://postgresql:5432/coolstore` | ConfigMap | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | — / — | Secret | Database credentials |
| `DB_POOL_MIN` / `DB_POOL_MAX` | `2` / `10` | ConfigMap | Agroal pool sizing |
| `DB_ACQUISITION_TIMEOUT` | `5S` | ConfigMap | Fail fast instead of hanging |
| `FLYWAY_MIGRATE_AT_START` | `true` | ConfigMap | Schema ownership |
| `REDIS_HOSTS` | `redis://redis:6379` | ConfigMap | Cart store endpoint |
| `QUARKUS_REDIS_PASSWORD` | — (empty) | Secret (`REDIS_PASSWORD`) | Optional Redis AUTH |
| `CART_TTL_SECONDS` | `86400` | ConfigMap | Cart expiry |
| `ARTEMIS_URL` | `tcp://amq-broker:61616` | ConfigMap | JMS broker |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | — / — | Secret | Broker credentials |
| `ORDERS_TOPIC` / `ORDERS_DLQ` | `topic/orders` / `orders.dlq` | ConfigMap | Destination names |
| `ORDERS_CONSUMER_ENABLED` | `true` | ConfigMap | Disable to run API-only |
| `ORDERS_MAX_DELIVERY_ATTEMPTS` | `5` | ConfigMap | App-side documentation of the broker contract; the broker enforces it |
| `AUDIT_STREAM_ENABLED` | `false` | ConfigMap | Audit emission (non-blocking) |
| `AUDIT_STREAM_HOST` / `_PORT` | `audit-collector` / `5044` | ConfigMap | Audit collector (optional) |
| `OIDC_ENABLED` | `false` | ConfigMap | Backend OIDC tenant (no path is protected) |
| `LOG_JSON` / `LOG_LEVEL` | `true` / `INFO` | ConfigMap | Structured logs on stdout for OpenShift logging |
| `SHUTDOWN_TIMEOUT` | `20s` | ConfigMap | Graceful shutdown (terminationGracePeriod 40 s) |
| `coolstore.json` | `SSO_ENABLED:false` | ConfigMap (file) | SPA runtime config |
| `keycloak.json` | realm `eap`, `/auth/` | ConfigMap (file) | SPA OIDC config |

---

## 7. Resource requirements

| Workload | CPU request / limit | Memory request / limit | Storage |
|---|---|---|---|
| `coolstore` (app) | 250m / 1 | 512Mi / 1Gi | none (read-only rootfs, `/tmp` emptyDir 128Mi) |
| `postgresql` | 250m / 1 | 512Mi / 1Gi | 10Gi RWO PVC |
| `redis` | 100m / 500m | 128Mi / 512Mi | emptyDir 256Mi (ephemeral) |
| `amq-broker` | 250m / 1 | 512Mi / 1Gi | emptyDir 1Gi (dev only) |

The JVM uses `-XX:MaxRAMPercentage=75` of the container limit (≈768 MiB heap at a 1Gi limit),
`-XX:+ExitOnOutOfMemoryError`, and `-Djava.io.tmpdir=/tmp`.

---

## 8. Health, security and operations

### Probes

| Probe | Path | Initial behaviour |
|---|---|---|
| startup | `/q/health/started` | 5 s period, 40 failures (≈200 s) for JVM + Flyway |
| liveness | `/q/health/live` | 10 s period, process-only checks |
| readiness | `/q/health/ready` | 5 s period; includes PostgreSQL, Redis, Artemis |

### Security

* Image: UBI 9, non-root 185, no secrets, read-only root filesystem, capabilities dropped.
* Runtime: OpenShift `restricted-v2` SCC; no `runAsUser` pinned so the namespace UID range is
  honoured; the image is group-0 readable.
* Network: TLS edge Route, HTTP redirect; NetworkPolicy limits ingress/egress.
* Secrets: OpenShift Secret, injected as environment variables; rotate by updating
  `coolstore-secrets` and restarting the rollout. For production use Vault/External Secrets
  or Sealed Secrets; `secret.yaml` in Git contains placeholders only.

### Rollback

```bash
oc rollout history deployment/coolstore
oc rollout undo deployment/coolstore          # previous image
# or repoint the Route to the legacy service (see docs/runbook.md)
```

Cart data lives in Redis and is intentionally ephemeral; orders persisted in PostgreSQL are
unaffected by a rollback.

---

## 9. Verification performed and limitations

| Check | Result |
|---|---|
| `rgctl discover --with-cfg --with-security --with-taint --with-harmonic` | ✅ completed (13 s, 37,183 nodes / 129,149 edges) |
| Dependency/blocker analysis (POM, config, graph `uses` edges) | ✅ completed |
| YAML syntax + kustomize build (`oc kustomize .`) | ✅ renders 14 objects, image rewritten to the internal registry |
| Server-side dry-run (`oc apply --dry-run=server -n <ns>`) for Deployment, Service, Route, ConfigMap, Secret, ServiceAccount, Redis and Artemis Deployments | ✅ accepted by the API server |
| Strict client validation (`oc create --dry-run=client --validate=strict`) for HPA, PDB, NetworkPolicy and the PostgreSQL StatefulSet | ✅ schema-valid (server-side dry-run blocked only by the sandbox service account's RBAC) |
| Probe paths match Quarkus SmallRye Health defaults and `application.properties` | ✅ static check |
| Dockerfile build (`podman build`) | ❌ not possible — no `podman`/`buildah` in the sandbox |
| Maven compile/test (`mvn verify`) | ❌ not possible — no JDK 21/Maven installed here (the execute stage ran `mvn verify` successfully with 67 tests / 85.3 % coverage) |
| Cluster smoke test (`oc apply -k .`, curl) | ❌ no cluster available |

---

## 10. Known issues and limitations

1. **Broker configuration is dev-grade.** `artemis.yaml` runs the UBI-based ArtemisCloud image
   with an `emptyDir` and default address settings (max-delivery-attempts 10). Production must
   use the AMQ Broker Operator or a managed broker with `max-delivery-attempts=5`,
   `dead-letter-address=orders.dlq` and persistent storage. The app property
   `ORDERS_MAX_DELIVERY_ATTEMPTS` documents the contract; it is not enforced by the app.
2. **PostgreSQL/Redis manifests are single-replica reference deployments.** Production should
   use a managed database/operator with backups and HA; Redis is a cache and can be replaced by
   a managed Redis with the same URL. The Red Hat S2I database images used here are designed
   for OpenShift's arbitrary-UID model; if the cluster's SCC rejects them, switch to the
   corresponding `postgresql:15` / `redis:7` imagestream images (same env contract).
3. **Keycloak is not shipped.** OIDC is disabled by default (backend parity: no path is
   protected). Enabling SPA SSO requires a Keycloak instance, importing `realm-export.json`,
   serving `/auth/` on the same host as the Route, and setting `OIDC_ENABLED=true` plus
   `SSO_ENABLED=true` in `coolstore.json`.
4. **Audit collector is not shipped.** `AUDIT_STREAM_ENABLED=false`; when enabled without a
   collector, events are dropped after a readiness probe and checkout is unaffected by design.
5. **`redis` AUTH is empty by default.** NetworkPolicy restricts access to the app; set
   `REDIS_PASSWORD` to enable `requirepass` (the deployment forwards it as
   `QUARKUS_REDIS_PASSWORD`).
6. **Maven Central (or a mirror) is required at build time.** The vendored audit jar is in
   `lib/maven-repo`; everything else is downloaded. Add a `settings.xml` mirror (or a Nexus
   proxy) in the CI builder if the cluster is egress-restricted.
7. **Base images are pinned by tag, not digest.** Pin `ubi9/openjdk-21*` by digest in the
   production pipeline; generate an SBOM (`syft`) and scan (`grype`/`trivy`) with a
   Critical/High gate before pushing.
8. **SPA config precedence needs one runtime check.** `QUARKUS_HTTP_STATIC_LOCATIONS` points at
   the ConfigMap mount first; verify `GET /coolstore.json` returns the ConfigMap value after
   the first deploy (the mounted file is `SSO_ENABLED:false` by default).
9. **Graph noise.** `bower_components` inflates the knowledge graph (29k JS functions, most
   PageRank hotspots, 330 cycles). Consider excluding vendored assets from `rgctl discover`
   (or from the source tree) to make future analyses meaningful.
10. **No backend authorization** (`docs/decisions.md` D6) — a pre-existing migration
    decision, not a containerization issue, but relevant for exposing the Route externally.

---

## 11. Next steps

1. Build the image in CI (`podman build` or the binary BuildConfig), publish to the internal
   registry, run SBOM + vulnerability scan.
2. Deploy to a scratch namespace with `oc apply -k .`; verify `/q/health/ready`, the SPA,
   `/services/products`, a full cart→checkout flow, and `/q/metrics`.
3. Replace the reference PostgreSQL/Redis/Artemis deployments with managed services or
   operators (and update the ConfigMap/NetworkPolicy accordingly).
4. Load-test the JVM/DB pool at the 1Gi limit and tune `DB_POOL_MAX`/HPA thresholds.
5. Configure Keycloak (`/auth/` route) and the audit collector if those features are required.
6. Wire monitoring/alerting on `/q/health/ready`, 5xx rate, JMS DLQ depth and HPA events.
7. Complete the cutover/rollback drill documented in `docs/runbook.md`.

---

## 12. References

- Red Hat UBI images: <https://catalog.redhat.com/software/containers/search>
- Quarkus container-image guide: <https://quarkus.io/guides/container-image>
- OpenShift security best practices: <https://docs.openshift.com/container-platform/latest/security/index.html>
- Application runbook: `docs/runbook.md` · Migration decisions: `docs/decisions.md` · Execution report: `mig-execute-workspace/EXECUTION_REPORT.md`
