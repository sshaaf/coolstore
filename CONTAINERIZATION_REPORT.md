# Containerization Report: CoolStore (Quarkus 3 / Java 21)

**Stage**: MigIQ *containerize* · **Target**: Red Hat OpenShift 4.x (restricted-v2 SCC)
**Repository**: CoolStore storefront and order backend, migrated in place from a Java EE 7 /
JBoss EAP 7.4 WAR monolith to Quarkus 3.15.1 / Jakarta EE 10 / Java 21.
**Date**: 2026-11-06

---

## 1. Executive summary

| | |
| --- | --- |
| Application | `coolstore` - storefront SPA + REST API (`/services/*`), orders, cart, pricing, audit |
| Language / framework | Java 21, Quarkus 3.15.1 (RESTEasy Reactive, Hibernate ORM 6, Flyway, SmallRye Reactive Messaging AMQP) |
| Build system | Maven (`pom.xml`, fast-jar packaging, vendored repo `lib/maven-repo`) |
| Base image | `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21` (builder: `ubi9/openjdk-21:1.21`) |
| Runtime port | `8080` (HTTP, `/services`, SPA, `/q/health`, `/q/metrics`) |
| Build strategy | Multi-stage Dockerfile (Maven build → fast-jar copy), OpenShift `Docker` BuildConfig |
| Container user | UID 1001 / arbitrary UID, non-root, read-only root filesystem |
| Migration complexity | **Low-Medium** - no filesystem, JNDI, host-binding or OS-specific blockers; runtime depends on 1 mandatory data store and 1 mandatory broker |

**Result**: the application containerizes cleanly with no code changes. Every previous
containerization blocker was already removed during the migration stage (WAR packaging,
JNDI lookups, file-based audit logging, WebLogic T3 calls). What remains is configuration:

1. an external **PostgreSQL 15** database (mandatory - readiness check + Flyway),
2. an **AMQP 1.0 broker** with a **multicast** address `orders` (mandatory for order events),
3. an optional **audit collector** (TCP, non-fatal) and optional **Keycloak** (SSO off by default).

---

## 2. Graph analysis findings (rgctl)

Index built for this stage with the containerization profile:

```bash
rgctl discover . --with-cfg --with-security --with-taint --with-harmonic
```

### 2.1 Index health

| Signal | Value |
| --- | --- |
| Files indexed | 1,310 (3 unparseable: vendored JS/markup) |
| Nodes / edges | 37,161 / 129,006 |
| Functions analyzed | 26,959 (CFG/PDG: 26,863; 0 high/0 medium complexity) |
| Communities | 24,799 (modularity 0.47) |
| Circular dependencies | 329 - all inside `src/main/resources/META-INF/resources/bower_components/**` (vendored AngularJS/Bootstrap/libs), none in `com.redhat.coolstore` |
| Edge census | `calls` 31,903 · `contains` 37,235 · `instantiates` 1,805 · `uses` 343 · `usesconfig` 25 · `references` 81 |
| Security scan (`--with-security`) | 9 potential secrets - all development defaults/templates, see §2.4 |
| Taint (`--with-taint`) | 5 flows reported by discover (see §2.4) |
| Java sources | 43 files = 29 main + 14 test - the application core, everything else is SPA assets |

> **Reading the graph correctly**: PageRank/community listings are dominated by
> `bower_components` (top hotspot is `Date`, then jQuery/moment/lodash helpers). That is
> vendored front-end JavaScript, not application logic. The only tool-wide action this
> implies is to exclude `src/main/resources/META-INF/resources/bower_components` from
> future graph queries (`rgctl ... --exclude-scope`), not a containerization action.

### 2.2 Application structure (community map)

Communities with application meaning (the rest are per-file JS clusters):

| Community | Members | Maps to |
| --- | --- | --- |
| `CatalogItemEntity` | 102 | `model` + `service` persistence layer (catalog, inventory, pricing) |
| `Infrastructure / Common Library` | 295 | `utils`, `audit`, configuration routes, CDI plumbing |

The application is a **single deployable unit** (one Quarkus process, one port). No community
boundary justifies splitting into multiple containers: `rest` → `service` → `model` is a
layered monolith with in-process cart state (`InMemoryCartStore`) shared across endpoints.
Splitting it would require externalising the cart store, which is out of scope and would
change the REST contract.

REST surface (graph: 3 classes annotated `@Path`): `ProductEndpoint`, `CartEndpoint`,
`OrderEndpoint`, all under `/services`.

### 2.3 External dependencies (graph + source)

| Dependency | Evidence | Kind | Container decision |
| --- | --- | --- | --- |
| PostgreSQL 15 | `quarkus-jdbc-postgresql`, `jakarta.persistence` imports (40), `db/migration/V1_*`, readiness `DatabaseHealthCheck` | **Mandatory** | `postgres.yaml` (dev/test StatefulSet) or managed DB; `DB_URL` config |
| AMQP 1.0 broker (Artemis/AMQ) | `quarkus-smallrye-reactive-messaging-amqp`, `@Channel("orders")`, `@Incoming("orders")`, `@Incoming("orders-inventory")` | **Mandatory** for order events | `artemis.yaml` (dev/test) or managed/operator broker; **multicast** address `orders` |
| Audit collector | `audit-logging-library` 2.0.0 `StreamableAuditLogger`, `audit.stream.*` config | Optional, non-fatal | external; `AUDIT_ENABLED` / `AUDIT_STREAM_HOST` |
| Keycloak | `StorefrontConfigRoutes` (`/keycloak.json`), `keycloak.*` config | Optional | external; `SSO_ENABLED=false` by default |
| Red Hat registry | `rhel9/postgresql-15`, `amq7/amq-broker-rhel8` | Image pull | cluster pull secret |

Config coupling measured by the graph: 25 `usesconfig` edges, all resolving to keys that are
already `${ENV:default}` placeholders in `application.properties` - i.e. every runtime value
is externalisable without a rebuild.

### 2.4 Platform-specific code and containerization blockers

Searched the graph and source for the usual blockers:

| Blocker class | Finding | Status |
| --- | --- | --- |
| Hardcoded filesystem paths | None. The legacy `FileSystemAuditLogger` and its `./device-inventory-audit-logs` directory were replaced by TCP streaming | ✅ no writable path needed |
| Log files on disk | None - Quarkus logs to stdout/stderr (`%prod` emits JSON) | ✅ |
| Temp directories | JVM/Quarkus need `/tmp` | ✅ `emptyDir` mounted at `/tmp` |
| Hardcoded `localhost` | 4 defaults: `AMQP_HOST`, `AUDIT_STREAM_HOST`, `keycloak.auth-server-url`, and an empty `DB_URL` (Dev Services) | ✅ all overridden by ConfigMap/Secret |
| Secrets in configuration | `quarkus.datasource.password=${DB_PASSWORD:coolstore}`, `...amqp.password=${AMQP_PASSWORD:admin}`, `secret.example.yaml` `CHANGE_ME` | ✅ dev defaults only; real values in OpenShift Secrets |
| JNDI / `java:jboss/*` / `java:/topic/*` | 0 occurrences (verified during migration) | ✅ no host bindings, no admin port |
| `javax.*` / EJB / WebLogic T3 | 0 occurrences / 0 packages | ✅ |
| OS-specific or native code | None (`Runtime.exec`, `ProcessBuilder`: 0 hits) | ✅ pure JVM |
| SQL string concatenation | None - all persistence through Hibernate/JPA (parameterised) | ✅ |
| Fixed host port / privileged port | `quarkus.http.port=8080` (unprivileged) | ✅ |

**Security scan note (9 potential secrets)**: each hit is a *development default* rather than a
leaked credential - `coolstore`/`admin` defaults in `application.properties`, `CHANGE_ME` values
in `secret.example.yaml`, and Keycloak realm export fields (`resetPasswordAllowed`,
`webAuthnPolicy*`) matched by the heuristic. None is baked into the image: the Dockerfile copies
only `pom.xml`, `lib/maven-repo`, `src`, and the built `target/quarkus-app`, and every credential
is injected at runtime from a Secret.

**Taint note (5 flows)**: discover's taint pass reports 5 flows, all inside the two network
sinks of this application - the audit TCP socket and the JDBC datasource. Manual review found no
string-built SQL, no shell/process execution and no filesystem sink reachable from request
payloads, so none of them is a containerisation blocker. Reachability should be re-confirmed
with `rgctl taint --sink <...>` once CFG artifacts are rebuilt for a security-focused review.

---

## 3. Containerization strategy

### 3.1 Base images

| Stage | Image | Why |
| --- | --- | --- |
| Builder | `registry.access.redhat.com/ubi9/openjdk-21:1.21` | JDK 21 + Maven preinstalled; no package installation, no root build host |
| Runtime | `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21` | JRE 21 only, `run-java.sh` (container-aware heap sizing), `/deployments` prepared for arbitrary UID, no compiler/Maven |

Both tags were verified to exist in `registry.access.redhat.com` (HTTP 200 on the manifest
endpoint). Tags are pinned to the JDK line; refresh the patch level on a schedule or rebuild
on base-image updates.

### 3.2 Build strategy - multi-stage

1. **Builder**: writable `HOME=/build` and a repository-local `.m2` (the build never runs as
   root and never writes to `/`); `pom.xml` + vendored `lib/maven-repo` copied first as a
   dependency-cache layer (best-effort `dependency:go-offline`), then `src`, then
   `mvn -Dmaven.test.skip=true package` producing `target/quarkus-app` (fast-jar).
2. **Runtime**: only `target/quarkus-app` is copied to `/deployments` - no Maven, no source,
   no `target/classes`, no test artifacts. Tests run in CI (`mvn verify`), not in the image.

Layering: 2 package-manager layers (curl for HEALTHCHECK) + 1 copy layer. `--chown=1001:0`
on every `COPY` keeps the group-0 write contract required by arbitrary-UID execution.

### 3.3 Security hardening applied

| Control | Implementation |
| --- | --- |
| Non-root | `USER 1001`; no `runAsUser` override in the Deployment so the namespace UID range can be used |
| Read-only root FS | `readOnlyRootFilesystem: true`; only `/tmp` is an `emptyDir` |
| Capabilities | `drop: [ALL]`, `allowPrivilegeEscalation: false` |
| seccomp | `RuntimeDefault` (pod level) |
| Service account token | `automountServiceAccountToken: false` (SA + pod) |
| Secrets | OpenShift Secrets, projected as env vars; no credential in any image layer |
| Probes | startup `/q/health/started`, liveness `/q/health/live` (dependency-free), readiness `/q/health/ready` (DB reachable) |
| Network | ingress only from `openshift-ingress`; egress restricted to DNS, PostgreSQL, AMQP, audit 6514, HTTPS 443 |
| TLS | Route with `edge` termination + `insecureEdgeTerminationPolicy: Redirect` |
| Healthcheck | `curl -fsS /q/health/live` (for podman/docker users; OpenShift uses the probes) |

### 3.4 JVM sizing

`JAVA_OPTIONS` binds Quarkus to `0.0.0.0` and installs the JBoss LogManager;
`JAVA_OPTS_APPEND` sets `-XX:MaxRAMPercentage=75.0` and `-XX:+ExitOnOutOfMemoryError`.
With the 512Mi limit the heap is ~384Mi, which matches the container-aware `run-java.sh` sizing
and the migrated app's steady-state footprint (~200Mi). Scale the limit and the heap moves with
it - no image rebuild needed.

---

## 4. Generated artifacts

All new artifacts are at the repository root so a single `oc apply -k .` (or
`oc apply -f <file>`) deploys the application and its dev/test dependencies.

### 4.1 Image definition

| File | Purpose |
| --- | --- |
| `Dockerfile` | Canonical multi-stage UBI 9 / OpenJDK 21 build |
| `Containerfile` | **Byte-identical copy** of `Dockerfile` - kept because `Makefile`, `README.md` and `docs/runbook.md` reference `podman build -f Containerfile` (verified identical in this stage) |
| `.dockerignore` | Build-context exclusions (mirrors the existing `.containerignore`) |

### 4.2 OpenShift manifests

| File | Kind(s) | Notes |
| --- | --- | --- |
| `imagestream.yaml` | `ImageStream` | `lookupPolicy.local: true` so `image: coolstore:latest` works in any namespace |
| `buildconfig.yaml` | `BuildConfig` | `Docker` strategy, `dockerfilePath: Dockerfile`, **binary** source → `oc start-build coolstore --from-dir=. --follow`; no Git URL/credentials required |
| `serviceaccount.yaml` | `ServiceAccount` | token not automounted |
| `configmap.yaml` | `ConfigMap` | non-sensitive configuration (datasource URL/user, broker, audit, cart, storefront) |
| `secret.example.yaml` | `Secret` ×3 | `coolstore-db`, `coolstore-broker`, `coolstore-oidc` - **template only**, `CHANGE_ME`, excluded from kustomize |
| `deployment.yaml` | `Deployment` | 2 replicas, rolling update `maxUnavailable: 0`, probes, hardened security context, `/tmp` emptyDir |
| `service.yaml` | `Service` | ClusterIP, 8080 → `http` |
| `route.yaml` | `Route` | edge TLS, HTTP→HTTPS redirect |
| `hpa.yaml` | `HorizontalPodAutoscaler` | 2-4 replicas, 70 % CPU, 300 s scale-down stabilisation |
| `pdb.yaml` | `PodDisruptionBudget` | `minAvailable: 1` |
| `networkpolicy.yaml` | `NetworkPolicy` | default-deny + explicit allows |
| `postgres.yaml` | `Service` + `StatefulSet` | PostgreSQL 15, 5Gi PVC, dev/test only |
| `artemis.yaml` | `Service` + `StatefulSet` | AMQ Broker 7 (AMQP 1.0 on 5672), 2Gi PVC, **multicast** address `orders`, dev/test only |
| `kustomization.yaml` | `Kustomization` | complete bundle (`oc apply -k .`) |

Consistency with the migration stage: the root bundle reuses the **same env/secret contract**
as the migrate-stage `deploy/` overlay (`QUARKUS_DATASOURCE_PASSWORD`, `AMQP_PASSWORD`,
`QUARKUS_DATASOURCE_JDBC_URL`, `AMQP_HOST`, `AUDIT_*`, `CART_TTL_SECONDS`), so one set of
Secrets serves both `oc apply -k .` and `oc apply -k deploy/`. The root bundle is a superset:
it adds the build pipeline (ImageStream + BuildConfig) and the two backing services. `deploy/`
is intentionally left untouched as the migration-stage app-only overlay.

---

## 5. Configuration reference

### 5.1 Environment variables

| Variable | Source | Default in image | Required | Purpose |
| --- | --- | --- | --- | --- |
| `QUARKUS_DATASOURCE_JDBC_URL` | ConfigMap | empty (Dev Services) | **yes** | `jdbc:postgresql://postgres:5432/coolstore` |
| `QUARKUS_DATASOURCE_USERNAME` | ConfigMap | `coolstore` | yes | DB user |
| `QUARKUS_DATASOURCE_PASSWORD` | Secret `coolstore-db` | `coolstore` (dev) | **yes** | DB password |
| `AMQP_HOST` / `AMQP_PORT` / `AMQP_USER` | ConfigMap | `localhost`/`5672`/`admin` | **yes** | Broker endpoint |
| `AMQP_PASSWORD` | Secret `coolstore-broker` | `admin` (dev) | **yes** | Broker password |
| `AUDIT_ENABLED` | ConfigMap | `true` | no | Emit audit events |
| `AUDIT_STREAM_HOST` / `_PORT` / `_PROTOCOL` | ConfigMap | `localhost`/`6514`/`TCP` | no | Audit collector |
| `CART_TTL_SECONDS` | ConfigMap | `1800` | no | Idle cart eviction |
| `COOLSTORE_MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT` | ConfigMap | `true`/empty | no | Served via `/coolstore.json` |
| `SSO_ENABLED`, `KEYCLOAK_*` | ConfigMap | `true`/`localhost:8081` | no | Served via `/keycloak.json`; disabled in the shipped ConfigMap |
| `QUARKUS_LOG_LEVEL` | ConfigMap | `INFO` | no | Log verbosity (`%prod` is JSON) |
| `JAVA_OPTIONS`, `JAVA_OPTS_APPEND` | image `ENV` | host binding, log manager, heap 75 % | no | JVM tuning (override per environment if needed) |

Build-time (not runtime-changeable) properties: `quarkus.rest.path=/services`,
`quarkus.smallrye-health.root-path=/q/health`, datasource kind, Flyway locations.

### 5.2 Resource requirements (per pod)

| Workload | CPU request/limit | Memory request/limit | Storage |
| --- | --- | --- | --- |
| `coolstore` | 100m / 500m | 256Mi / 512Mi | `/tmp` emptyDir |
| `postgres` (dev/test) | 100m / 500m | 256Mi / 512Mi | 5Gi RWO |
| `artemis` (dev/test) | 100m / 500m | 384Mi / 768Mi | 2Gi RWO |

### 5.3 Health endpoints

| Endpoint | Consumer | Behaviour |
| --- | --- | --- |
| `/q/health/started` | startupProbe | Quarkus bootstrap + Flyway migration complete |
| `/q/health/live` | livenessProbe, Docker HEALTHCHECK | Self only - a dead DB never restarts the pod |
| `/q/health/ready` | readinessProbe | DB connectivity (`DatabaseHealthCheck`, `isValid(2)`) |
| `/q/metrics` | Prometheus | Micrometer/Prometheus registry |

---

## 6. Deployment instructions

```bash
# 0. Prerequisites: oc logged in, Red Hat registry pull secret on the cluster
oc new-project coolstore

# 1. Secrets first (never applied from the template file)
oc -n coolstore create secret generic coolstore-db \
  --from-literal=QUARKUS_DATASOURCE_PASSWORD='<postgres-password>'
oc -n coolstore create secret generic coolstore-broker \
  --from-literal=AMQP_PASSWORD='<artemis-password>'

# 2. Everything else (SA, ConfigMap, ImageStream, BuildConfig, DB, broker, app, route)
oc apply -k .

# 3. Build the image from the working tree (binary build, uses Dockerfile)
oc -n coolstore start-build coolstore --from-dir=. --follow

# 4. Wait for the rollout and smoke-test
oc -n coolstore rollout status deployment/coolstore
ROUTE=$(oc -n coolstore get route coolstore -o jsonpath='{.spec.host}')
curl -fsS "https://$ROUTE/q/health/ready"
curl -fsS "https://$ROUTE/services/products" | head -c 200
curl -fsS -X POST "https://$ROUTE/services/cart/demo/329299/1"
curl -fsS -X POST "https://$ROUTE/services/cart/checkout/demo"
```

Alternative image path (no in-cluster build): `make image push` uses the same Dockerfile via
`podman`/`buildah`, or `mvn package -Dquarkus.container-image.push=true` uses Jib.

Reduce the manifest set when the backing services are managed:

```bash
# managed PostgreSQL + managed/operator AMQP broker
oc apply -f serviceaccount.yaml,configmap.yaml,imagestream.yaml,buildconfig.yaml,\
deployment.yaml,service.yaml,route.yaml,hpa.yaml,pdb.yaml,networkpolicy.yaml
# then edit configmap.yaml: QUARKUS_DATASOURCE_JDBC_URL and AMQP_HOST
```

### Rollback

```bash
oc -n coolstore rollout history deployment/coolstore
oc -n coolstore rollout undo deployment/coolstore            # previous revision
oc -n coolstore rollout undo deployment/coolstore --to-revision=2
oc -n coolstore rollout status deployment/coolstore
```

---

## 7. Verification performed

| Check | Command | Result |
| --- | --- | --- |
| Graph index / communities | `rgctl -f json metrics --pagerank`, `rgctl -f json communities list` | ✅ 37,161 nodes, 24,799 communities |
| Manifest rendering | `kubectl kustomize .` | ✅ 14 objects (BuildConfig, ConfigMap, Deployment, HPA, ImageStream, NetworkPolicy, PDB, Route, 3 × Service, ServiceAccount, 2 × StatefulSet) |
| Manifest → OpenShift API schema | `oc create --dry-run=client -f <each file>` (client v4.22, K8s 1.36) | ✅ all 13 files accepted, including `Route`, `BuildConfig`, `ImageStream` |
| Legacy overlay still valid | `kubectl kustomize deploy` | ✅ unchanged, renders 8 objects |
| Base image tags exist | registry manifest `HEAD` for `ubi9/openjdk-21:1.21`, `ubi9/openjdk-21-runtime:1.21` | ✅ HTTP 200 |
| Build files in sync | `Dockerfile` vs `Containerfile` | ✅ byte-identical |
| Image build (`podman build`) | - | ⚠️ not run: no `podman`/`buildah`/`docker` in this environment |
| `mvn verify` inside the image | - | ⚠️ not run: no JDK/Maven on the host; tests are a CI concern |
| Vulnerability scan (grype/trivy) | - | ⚠️ not run (no container engine, no scanner) |
| Cluster deploy + probes | - | ⚠️ not run (deploy stage): the session's cluster identity lacks create/read rights on the target namespace |

Nothing in the static verification failed. The three ⚠️ rows are environment limitations, not
defects; the deploy stage must re-run `oc start-build`, the rollout, and an image scan
(`trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed coolstore:latest`).

---

## 8. Known issues, limitations, and follow-ups

1. **AMQP routing type is the one silent-failure risk.** Address `orders` must be **multicast**:
   two channels consume it (`OrderEventConsumer`, `InventoryNotificationConsumer`). With an
   anycast queue only one consumer sees each event and order persistence or low-stock warnings
   disappear without an error. `artemis.yaml` deliberately sets `AMQ_ADDRESSES=orders` and no
   `AMQ_QUEUES` (Artemis default routing type is multicast); verify on any external broker.
2. **Backing services in `postgres.yaml` / `artemis.yaml` are dev/test only** (single replica,
   RWO PVC, `registry.redhat.io` images → cluster pull secret required). Production should use
   a managed database and AMQ Broker Operator (or managed AMQP 1.0), with `range`/`AMQP_HOST`
   updated in the ConfigMap.
3. **Audit collector is not deployed.** `AUDIT_ENABLED=true` with an unresolvable
   `audit-collector` host is safe - `StreamableAuditLogger` opens the socket per event inside
   `logEventAsync` and logs failures; it never propagates them. Set `AUDIT_ENABLED=false` if no
   collector is intended, or point `AUDIT_STREAM_HOST` at the real collector.
4. **SSO is disabled** in the shipped ConfigMap (`SSO_ENABLED=false`). Enable it and set
   `KEYCLOAK_AUTH_SERVER_URL`/`KEYCLOAK_RESOURCE` (plus the optional `coolstore-oidc` Secret) to
   restore the Keycloak login flow; egress 443 is already allowed.
5. **Cart state is in-memory per pod** (`InMemoryCartStore`, TTL 1800 s). With 2+ replicas a cart
   is only visible to the pod that created it. Enabling session affinity
   (`sessionAffinity: ClientIP`) on the Service is the minimal mitigation; a shared store
   (Redis) is the real fix and was out of scope for the migration.
6. **`Containerfile` and `Dockerfile` are duplicates** - kept byte-identical for the existing
   `Makefile`/docs. Change both, or switch the Makefile to `-f Dockerfile` and delete
   `Containerfile`.
7. **Base image patch level** (`:1.21`) is a moving tag within the JDK 21 line. Pin by digest in
   regulated environments and rebuild on Red Hat security advisories.
8. **No `podman-compose.yml` is shipped.** Local development does not need one: Quarkus Dev
   Services starts PostgreSQL automatically and the `%test` profile swaps the AMQP connector for
   the in-memory one (`mvn quarkus:dev`). The dev/test `postgres.yaml` + `artemis.yaml` cover the
   "everything in a cluster" case.
9. **Graph noise from `bower_components`** inflates community/hotspot metrics (329 "cycles",
   top PageRank `Date`). Exclude `src/main/resources/META-INF/resources/bower_components` in
   later analysis; it is static SPA content and is copied into the fast-jar as resources.

---

## 9. Next steps

1. Deploy stage: create namespace/secrets, `oc start-build`, verify rollout + probes.
2. Scan the built image (trivy/grype, fail on fixable CRITICAL) and record the digest.
3. Point `QUARKUS_DATASOURCE_JDBC_URL` / `AMQP_HOST` at managed services; drop `postgres.yaml`
   and `artemis.yaml` from the kustomization.
4. Add a Tekton pipeline (platform-managed) that runs `mvn verify` → `start-build` → rollout.
5. Decide on cart externalisation (Redis) before scaling beyond a single active pod.
6. Enable the audit collector and Keycloak before production cutover.

---

## 10. References

- Red Hat UBI catalog: <https://catalog.redhat.com/software/containers/search>
- OpenShift security / SCC: <https://docs.openshift.com/container-platform/latest/security/index.html>
- Quarkus container-image guide: <https://quarkus.io/guides/container-image>
- Repository docs: `docs/runbook.md`, `docs/adr/001-messaging.md`, `docs/audit-logging.md`
