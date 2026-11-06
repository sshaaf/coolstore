# Containerization Report: CoolStore Monolith

**Application**: CoolStore storefront + checkout API (AngularJS SPA, JAX-RS `/services`, CDI services,
Hibernate ORM/Flyway on PostgreSQL, Kafka order pipeline, Keycloak SSO, NDJSON audit over TCP)
**Migrated stack**: Quarkus 3.27.6 (LTS) / Java 21 / Jakarta EE 10 (from JBoss EAP 7.4 / Java EE 7)
**Deployment target**: OpenShift 4.x (restricted-v2 SCC)
**Stage date**: 2026-11-06
**Status**: ✅ Image definition, manifests and documentation complete and statically verified.
⚠️ `podman build`, image scan and cluster apply could not run here (no Podman, no JDK/Maven, no
cluster) — they are the first two gates in *Next steps*.

---

## 1. Executive summary

| | |
|---|---|
| Language / framework | Java 21, Quarkus 3.27.6, Jakarta EE 10 (JAX-RS, CDI, JPA, Bean Validation) |
| Build system | Maven 3.9 (`pom.xml`, `quarkus-maven-plugin`, fast-jar) |
| Runtime port | 8080 (HTTP: SPA, `/services/**`, `/q/health/*`, `/q/metrics`) |
| Base images | `registry.access.redhat.com/ubi9/openjdk-21:1.24` (build) + `ubi9/openjdk-21-runtime:1.24` (run) |
| Build strategy | Two-stage, rootless build, fast-jar only in the runtime layer |
| Process user | UID 185 in the image; OpenShift assigns a namespace UID (GID 0 friendly) |
| Manifests | 8 root manifests + `kustomization.yaml`; `deploy/base` mirrors for the dev/prod overlays |
| Persistent storage | **None required** — the app writes only to `/tmp` (`emptyDir`) |
| External services | PostgreSQL, Kafka (AMQ Streams), Keycloak, audit collector — all external to the pod |
| Migration complexity | **Low** — a 12-factor monolithic web app with no filesystem or OS coupling |

The migrated application is already container-shaped: configuration is environment-driven, the only
writable path is `/tmp`, and there is no local file storage, no scheduled job, no OS binary and no
hardcoded hostname in production configuration. Containerisation is therefore a packaging exercise,
not a code change — **no application source file was modified in this stage**.

---

## 2. Graph analysis findings

Index rebuilt for this stage (daemon-free, in-repo artifacts under `.rgctl/`):

```bash
rgctl discover . --with-cfg --with-security --with-taint --with-harmonic
rgctl -f json metrics --pagerank          # index + hotspot gate
rgctl -f json communities list            # subsystem gate
```

| Metric | Value |
|---|---|
| Files indexed | 1,308 |
| Nodes / edges | 37,146 / 107,563 (`rgctl status`, `index_current=true`) |
| Functions | 26,947 (avg cyclomatic complexity 1.0; 0 high/medium) |
| Classes / interfaces / annotations | 267 / 12 / 38 |
| Communities | 24,821 (modularity 0.47); 92 with >20 members |
| Languages | JavaScript 29,427 (AngularJS `bower_components`), Java 885, HTML 47, SQL/YAML/Bash remainder |
| Circular dependencies | 329 — all inside vendored JS (`bower_components`), none in `com.redhat.coolstore` |
| Hotspots | PageRank top is dominated by vendor JS (`Date`, lodash/moment helpers); no Java class in the top 20 — the business code is small and evenly coupled |
| Rule engine | `rgctl -f json rules run rules` → 5 rules, **0 violations** |

### 2.1 Structure

Java is 24 main classes in five packages (`model`, `rest`, `service`, `audit`, `utils`) plus 14 test
classes. The named communities that matter for deployment are `Infrastructure / Common Library`
(295 members), `Order` (77) and `CatalogItemEntity` (43); the rest of the 24,821 communities are
per-function artifacts of the vendored JavaScript.

`rgctl -f json blast-radius save --file .../OrderService.java` → **2 direct callers, impact zone 4**
(both endpoints): the checkout path is a single CDI bean, which is why the monolith stays one
container instead of a service split.

### 2.2 Import census (framework evidence)

`com.redhat` 65, `java.util` 58, `jakarta.persistence` 33, `jakarta.enterprise` 22, `jakarta.ws` 21,
`jakarta.inject` 16, `com.enterprise` 13 (vendored audit library), `java.io` 14 (streams only), plus
JUnit 48 in test scope. No `javax.*`, no JBoss modules, no WebLogic/`weblogic/**` imports — the
Jakarta EE 10 migration is complete.

### 2.3 External dependencies (from the graph and configuration)

| Dependency | Evidence | How it is provided |
|---|---|---|
| PostgreSQL | `quarkus.datasource.*` (Agroal + Hibernate ORM 6), `%prod.quarkus.datasource.jdbc.url=${DB_URL}` | External/managed service or in-cluster operator; **not** shipped as a StatefulSet because the schema is shared with the EJB-era database (Flyway, byte-identical checksums) and rollback is code-only |
| Kafka / AMQ Streams | `mp.messaging.*` smallrye-kafka channels `orders-out` / `orders`, `KAFKA_BOOTSTRAP_SERVERS` | External broker; topic `orders`, group `coolstore-order-service` |
| Keycloak | `quarkus-oidc`, `OIDC_AUTH_SERVER_URL`/`OIDC_REALM`/`OIDC_CLIENT_ID`, SPA `/keycloak.json` generated at runtime | External; realm from `realm-export.json` |
| Audit collector | `audit-logging-library` 2.0.0 `StreamableAuditLogger` → `AUDIT_STREAM_HOST:PORT` (TCP/NDJSON) | External; `AUDIT_ENABLED=false` makes the logger a no-op |

No cache, no message broker embedded in the JVM, no LDAP, no SMTP, no object storage.

### 2.4 Platform-specific code / containerisation blockers

| Check | Result |
|---|---|
| Hardcoded filesystem paths (`/var`, `/opt`, `C:\`) | **None** — the only file-adjacent API is `java.io` streams for classpath resources |
| Local file storage / uploads | **None** — no `new File`, `Files.*`, no `Multipart` handling; the audit logger writes to a socket, not to disk |
| Log files on disk | **None** — JSON console logging to stdout (`LOG_JSON=true`) |
| `localhost` assumptions | Only in `%dev`/default values (`application.properties` datasource and `audit.stream.host`); `%prod` has no localhost default and fails fast without `DB_URL`/`DB_USER`/`DB_PASSWORD` |
| Hardcoded environment values in the web layer | **None** — `/coolstore.json` and `/keycloak.json` are generated from configuration (`ConfigEndpoint`, `SpaConfigRoutes`) |
| Windows/OS-specific calls | **None** |
| Secrets in the image | **None baked in**. `rgctl --with-security` reported 1 potential secret: the *dev-only* default `DB_PASSWORD:postgresPW` in `application.properties`, which `%prod` overrides with a mandatory environment variable. It never reaches a production container. |
| Taint analysis (`--with-taint`) | 1 flow flagged (1 vulnerable) by the heuristic. Manual review: all persistence uses the JPA Criteria API (`em.createQuery(criteria)`), there is no string-concatenated SQL, no process execution and no filesystem sink, so nothing actionable for the container. |
| Ports | 8080 only |
| Background workers | The Kafka consumer runs in-process (`@Incoming("orders")`) — same pod, no separate worker deployment |

### 2.5 Build requirements (what must exist at build time, not runtime)

* JDK 21 + Maven 3.9 (the vendored `audit-logging-library` 2.0.0 must resolve from `lib/repo`).
* Maven Central (Quarkus BOM/extension download). No internal Red Hat repository is required — the
  builder image's `jboss-settings.xml` defines no mirror.
* Nothing else: no npm/bower install (the `bower_components` tree is committed), no native compiler,
  no database or broker at build time (tests use H2 + in-memory messaging, and they are skipped in
  the image build).

---

## 3. Containerization strategy

### 3.1 Base image selection

| Stage | Image | Why |
|---|---|---|
| Build | `registry.access.redhat.com/ubi9/openjdk-21:1.24` | Red Hat UBI 9 with JDK 21 **and Maven 3.9**; runs as UID 185 with a group-writable `HOME`, so the build needs no root and also works in arbitrary-UID build pods. Tag `1.20` (Maven 3.8.5) was rejected as too old for the Quarkus 3.27 build; `1.23`/`1.24` ship Maven 3.9. |
| Runtime | `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24` | JDK 21 only on a **ubi-micro** base (no package manager, no shell → small attack surface), UID 185, `/deployments` convention, `curl` available for the healthcheck. |

Both tags were verified to exist and were inspected (config blobs) for user, home, entrypoint and
Maven version:
`/v2/ubi9/openjdk-21/manifests/1.24` → `User=185, HOME=/home/default, MAVEN_VERSION=3.9`;
`/v2/ubi9/openjdk-21-runtime/manifests/1.24` → `User=185, Cmd=/opt/jboss/container/java/run/run-java.sh`.

### 3.2 Build strategy

```
build (UID 185, /home/default/build)          runtime (UID 185, /deployments)
  COPY pom.xml, lib/repo                        COPY quarkus-app/{lib,app,quarkus}/
  COPY src                                      COPY quarkus-run.jar
  mvn -B -ntp -DskipTests package               ENV HOME=/tmp, JAVA_TOOL_OPTIONS=...
  test -f target/quarkus-app/quarkus-run.jar    ENTRYPOINT ["java","-jar",".../quarkus-run.jar"]
```

* **Multi-stage**: Maven, the local repository and the sources never reach the runtime layer.
* **Rootless build**: works with rootless Podman and with OpenShift Pipelines (buildah) without
  `USER 0`; the work directory is created by the image user.
* **Vendored artifact**: `pom.xml` declares `file://${project.basedir}/lib/repo`, and only
  `lib/repo` (20 KB) is copied — the loose `lib/*.jar` files are excluded via `.dockerignore`. No
  `install-file` step, no `systemPath`.
* **Tests**: not run in the image (`-DskipTests` compiles them). The 84-test suite is a pipeline
  gate (`mvn -B verify`) before the image build.
* **`container` profile** in `pom.xml` still works for `mvn package -Dquarkus.container-image.build`.

### 3.3 Runtime contract

* Profile: the packaged fast-jar runs the `%prod` profile (no `%prod` value has a localhost default).
* All configuration is environment-driven (ConfigMap/Secret); the image contains no URL, no
  credential and no environment name.
* Writable paths: `/tmp` only — `HOME=/tmp`, `-Duser.home=/tmp`, `-Djava.io.tmpdir=/tmp`, and the
  Deployment mounts an `emptyDir` there with `readOnlyRootFilesystem: true`.
* JVM: `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager …` (overridable per environment
  without rebuilding).
* **No shell** in the runtime image: `ENTRYPOINT` and `HEALTHCHECK` are exec form and the Deployment
  has no `sh -c` `preStop` hook. Shutdown is SIGTERM + `quarkus.shutdown.timeout=30s` inside
  `terminationGracePeriodSeconds: 45`.

---

## 4. Generated artifacts

### 4.1 Image

| | |
|---|---|
| Name | `coolstore:1.0.0` (also `image-registry.openshift-image-registry.svc:5000/<namespace>/coolstore:<tag>`) |
| Build | `podman build -t coolstore:1.0.0 .` |
| Expected size | ~150–260 MB compressed: ubi9 openjdk-21-runtime (~120 MB) + fast-jar, of which ~48 MB is the committed `bower_components` static tree (AngularJS 1.x, accepted EOL risk) |
| Layers | 1 dependency/build layer + 4 fast-jar `COPY` layers |

### 4.2 Manifests (repository root — canonical)

| File | Object | Notes |
|---|---|---|
| `Dockerfile` | image build | two-stage, UBI 9, non-root, healthcheck (renamed from `Containerfile` so the platform pipeline and `podman`/`docker` all use one file) |
| `.dockerignore` | build context | excludes `.git`, `target`, docs, plans, manifests, realm export, loose jars |
| `kustomization.yaml` | Kustomization | `oc apply -k .`; deliberately excludes `secret.yaml` |
| `configmap.yaml` | ConfigMap `coolstore-config` | 22 non-secret runtime settings |
| `secret.yaml` | 3 Secrets | placeholders only (`coolstore-db`, `coolstore-broker`, optional `coolstore-oidc`) |
| `serviceaccount.yaml` | ServiceAccount `coolstore` | token not automounted |
| `deployment.yaml` | Deployment `coolstore` | 2 replicas, `maxUnavailable: 0`, probes, hardened, emptyDir `/tmp`, ImageStream trigger |
| `service.yaml` | Service `coolstore` | ClusterIP, named port `http` |
| `route.yaml` | Route `coolstore` | edge TLS + HTTPS redirect, `balance: source` (per-replica cart state) |
| `networkpolicy.yaml` | NetworkPolicy `coolstore` | ingress from routers only; egress DNS + 5432/9092/443/9000 |
| `hpa.yaml` | HPA `coolstore` | 2–6 replicas at 70 % CPU, 5 min scale-down window |
| `deploy/base/*.yaml` | kustomize base | mirrors of the root manifests (kustomize cannot read above its root) |
| `deploy/overlays/{dev,prod}` | overlays | namespace, image tag/digest, replica count |

**No PVC, no StatefulSet, no `podman-compose.yml`**: the application persists nothing locally, and
PostgreSQL/Kafka/Keycloak are environment services, not part of this workload. Shipping a
single-replica Postgres would contradict the shared-database rollback strategy in `MIGRATION.md`.

### 4.3 Documentation

`docs/container.md` (image + env contract), `docs/openshift.md` (manifests, probes, secrets,
dependencies, cutover/rollback), `README.md` (build & deploy), `MIGRATION.md` (packaging row).

---

## 5. Security hardening applied

**Image**

- ✅ Red Hat UBI 9 for both stages; runtime is ubi-micro (no shell, no package manager).
- ✅ Non-root: `USER 185`, every copied file `--chown=185:0` so arbitrary-UID execution works.
- ✅ Multi-stage build: no Maven, no sources, no repository in the runtime layer.
- ✅ No credentials, URLs or environment values in any layer (verified by static check).
- ✅ `HEALTHCHECK` via `curl` on `/q/health/live`.

**Runtime (Deployment)**

- ✅ `runAsNonRoot: true`, no explicit `runAsUser` (restricted-v2 assigns the namespace UID — a
  hardcoded `185` would be rejected by `MustRunAsRange` outside the namespace's range).
- ✅ `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`.
- ✅ `seccompProfile: RuntimeDefault`, `automountServiceAccountToken: false`.
- ✅ Resource requests/limits set (250m/512Mi → 1 CPU/1Gi).
- ✅ Secrets from `secretKeyRef`, never from the image or the ConfigMap.
- ✅ Route terminates TLS at the edge and redirects plain HTTP.
- ✅ Default-deny NetworkPolicy with an explicit allow-list (including DNS, which a naive egress
  policy breaks).

**Verified not present**: no `latest` base tag, no `USER 0` in the final stage, no shell-form
`RUN`/`ENTRYPOINT` in the runtime stage, no real secret value in `secret.yaml`.

---

## 6. Deployment instructions

```bash
# 0. project + credentials
oc new-project coolstore-dev

# 1. Secrets first (placeholders in secret.yaml are not credentials)
oc create secret generic coolstore-db -n coolstore-dev \
  --from-literal=url=jdbc:postgresql://postgres:5432/postgresDB \
  --from-literal=username=postgresUser --from-literal=password="$(openssl rand -hex 16)"
oc create secret generic coolstore-broker -n coolstore-dev \
  --from-literal=bootstrap-servers=coolstore-kafka-bootstrap:9092

# 2. Build and push (or let the Tekton pipeline do it)
podman build -t coolstore:1.0.0 .
podman tag coolstore:1.0.0 image-registry.openshift-image-registry.svc:5000/coolstore-dev/coolstore:dev
podman push image-registry.openshift-image-registry.svc:5000/coolstore-dev/coolstore:dev

# 3. Deploy
oc apply -k .                       # in the project, or:
oc apply -k deploy/overlays/dev

# 4. Verify
oc rollout status deployment/coolstore
oc get route coolstore -o jsonpath='{.spec.host}'
curl -k https://$(oc get route coolstore -n coolstore-dev -o jsonpath='{.spec.host}')/q/health/ready
```

Keycloak: import `realm-export.json` (realm `eap`, public client `eap-app`) and set
`OIDC_AUTH_SERVER_URL` in the ConfigMap to the Keycloak Route; redirect URIs must include the
CoolStore Route hostname.

---

## 7. Configuration reference

| Variable | Source | Required | Notes |
|---|---|---|---|
| `DB_URL` | Secret `coolstore-db/url` | yes | prod has no default → fail fast |
| `DB_USER` | ConfigMap | yes | `postgresUser` |
| `DB_PASSWORD` | Secret `coolstore-db/password` | yes | never in the image |
| `KAFKA_BOOTSTRAP_SERVERS` | Secret `coolstore-broker/bootstrap-servers` | yes | broker listener 9092 |
| `ORDERS_TOPIC`, `ORDERS_CONSUMER_GROUP` | ConfigMap | no | `orders`, `coolstore-order-service` |
| `OIDC_ENABLED`, `OIDC_REALM`, `OIDC_CLIENT_ID`, `OIDC_AUTH_SERVER_URL` | ConfigMap | when SSO on | backend bearer protection stays opt-in |
| `OIDC_CLIENT_SECRET` | Secret `coolstore-oidc` (optional) | no | confidential clients only |
| `AUDIT_ENABLED`, `AUDIT_STREAM_HOST/PORT/PROTOCOL` | ConfigMap | no | `false` ⇒ no-op logger |
| `QUARKUS_HTTP_PORT`, `QUARKUS_SHUTDOWN_TIMEOUT` | ConfigMap | no | 8080, 30s |
| `DB_POOL_MIN_SIZE/MAX_SIZE`, `CART_STORE_*`, `ORDERS_DEDUPE_MAX_ENTRIES` | ConfigMap | no | tuning only |
| `LOG_JSON`, `LOG_LEVEL` | ConfigMap | no | prod defaults `true`/`INFO` |
| `POD_NAME`, `POD_NAMESPACE` | Downward API | no | log correlation |

**Resources**: request 250m CPU / 512Mi, limit 1 CPU / 1Gi (`MaxRAMPercentage=75` → ≤ 768Mi heap,
leaving room for metaspace and direct buffers). For a 2-replica dev project, 250m/512Mi requests fit
a 2 CPU / 2Gi namespace.

---

## 8. Health and monitoring

| Probe | Path | Initial delay / period | Purpose |
|---|---|---|---|
| startup | `/q/health/started` | 0 s / 5 s, 24 failures | Flyway migration + DB/Kafka connection before liveness starts |
| readiness | `/q/health/ready` | / 10 s, 3 failures | DB (and broker) health; DOWN removes the pod from the Service without a restart |
| liveness | `/q/health/live` | / 15 s, 3 failures | JVM liveness only |

Metrics: Micrometer/Prometheus at `/q/metrics` (scrape with the user-workload monitoring stack).
Logs: JSON on stdout (`LOG_JSON=true`) with `POD_NAME`/`POD_NAMESPACE` for correlation.
`/health.jsp` from the EAP era no longer exists.

---

## 9. Verification performed in this stage

| Check | Command | Result |
|---|---|---|
| Graph index | `rgctl discover . --with-cfg --with-security --with-taint --with-harmonic` | 1,308 files → 37,146 nodes / 128,854 edges, 24,821 communities, 13.2 s |
| Index freshness | `rgctl -f json status` | `index_current=true`, 0 dirty files |
| Communities gate | `rgctl -f json communities list` | 24,821 communities (non-empty) |
| Rules gate | `rgctl -f json rules run rules` | 5 rules, 0 violations |
| Base image existence | registry v2 tag/manifest API for `1.24` (both images) | 200 OK; user/home/Maven/entrypoint inspected |
| Dockerfile static checks | `/tmp/validate_dockerfile.py` | pass: UBI-pinned stages, non-root runtime, exec-form only, no baked secrets, COPY sources exist, fast-jar layout |
| Manifest static checks | `/tmp/validate_manifests.py` | pass: 10 documents parse; selector/probe/security/resource/secret-placeholder assertions |
| Kustomize build | `oc kustomize .` / `deploy/base` / `deploy/overlays/{dev,prod}` | all build clean |
| **Not executed** | `podman build`, `grype`/Trivy scan, `syft` SBOM, `oc apply` to a cluster, `@QuarkusIntegrationTest` | no Podman/Java in this environment and no cluster access |

Static YAML validation used PyYAML; kustomize validation used the `oc kustomize` builtin. No
`oc apply --dry-run` was possible (it requires API discovery against a live cluster).

---

## 10. Known issues and limitations

1. **Image never built here** — the first real gate is `podman build`; watch for the Maven 3.9
   dependency download and for the 48 MB static tree in the build context (`.git` is excluded, so
   the context is ~60 MB).
2. **AngularJS 1.x + `bower_components`** — EOL front-end assets dominate image size and CVE
   surface. Mitigation: static files only, no server-side execution; front-end rewrite is a
   separate epic. Record the scan findings as accepted.
3. **Cart state is per replica** — Route uses `balance: source`; scaling down loses carts pinned to
   the removed pod. Follow-up: Infinispan/Redis store.
4. **Audit collector is assumed to exist** — set `AUDIT_ENABLED=false` where it does not; the logger
   is a no-op then. A failing collector cannot fail a checkout (`logEventAsync`).
5. **OIDC backend protection is off by default** — `/services/**` stays unauthenticated until
   `OIDC_ENABLED=true`, which needs product sign-off and a Keycloak Route in the ConfigMap.
6. **NetworkPolicy defaults** — the egress rule allows the dependency ports to any namespace. Narrow
   it with `namespaceSelector`/`ipBlock` once the real topology is known.
7. **`secret.yaml` holds placeholders** — it must never be applied as-is; secrets are created out of
   band (SealedSecrets/Vault recommended).
8. **No direct TLS to PostgreSQL/Kafka** — in-cluster traffic is plaintext; enable broker/DB TLS and
   the corresponding `DB_URL`/Kafka properties if the platform requires it.

---

## 11. Rollback

The database schema is unchanged (Flyway migrations byte-identical, no new version), so rollback is
code only:

```bash
oc rollout undo deployment/coolstore                 # previous image revision
oc rollout history deployment/coolstore              # list revisions
# or pin the previous digest
oc set image deployment/coolstore coolstore=<registry>/coolstore@sha256:<previous-digest>
```

Platform-level rollback (back to JBoss EAP 7.4 with `ROOT.war`) is documented in `MIGRATION.md` and
needs no schema change.

---

## 12. Next steps

1. ⏳ `podman build -t coolstore:1.0.0 .` on a machine with registry access, then `podman run` with
   the full env set and check `/q/health/ready`.
2. ⏳ Scan (`grype`/Trivy) + SBOM (`syft -o spdx-json`); record accepted AngularJS findings.
3. ⏳ Push through the Tekton pipeline, confirm the `coolstore:latest` ImageStreamTag trigger rolls
   the Deployment.
4. ⏳ `docs/openshift.md` §Verification on a stage cluster: probes, 2-replica consumption, rolling
   update under load, `/q/metrics` scrape.
5. ⏳ p95 comparison against the EAP baseline (`docs/baseline.md`).
6. ⏳ Decide the cart-store follow-up and the durable duplicate-delivery guard.
7. ⏳ Sign-off on backend bearer protection (`OIDC_ENABLED=true`) before production.

## 13. References

- Red Hat UBI images: <https://catalog.redhat.com/software/containers/search>
- OpenShift SCC / arbitrary UID: <https://docs.openshift.com/container-platform/latest/authentication/managing-security-context-constraints.html>
- Quarkus container images: <https://quarkus.io/guides/container-image>
- `docs/container.md`, `docs/openshift.md`, `MIGRATION.md`
