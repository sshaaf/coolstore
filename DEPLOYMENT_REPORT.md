# OpenShift Deployment Report — CoolStore

**Status: ✅ DEPLOYED AND VERIFIED**

| Item | Value |
|---|---|
| Cluster API | `https://172.30.0.1:443` |
| Namespace | `coolstore-migiq` |
| Application | `coolstore` (Quarkus 3.40 / Java 21 fast-jar) |
| Container image | `image-registry.openshift-image-registry.svc:5000/coolstore-migiq/coolstore:migiq` |
| Image digest | `sha256:1e7ce73fef3faa1104b9fdbd7682715376e3e1a324275c77bb99c2e227ddeda2` |
| Source | `https://github.com/sshaaf/coolstore` @ `konveyor/migration-1793979085` (`70246f5`) |
| Tekton PipelineRun | `migiq-deploy-coolstore-2` — **Succeeded** |
| Route URL | **https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com** |

---

## 1. Build & deploy pipeline

The image was built **in Tekton** (no local `podman`/`buildah` in the agent pod) by the
pipeline `migiq-build-deploy`:

| Task | Name | Outcome |
|---|---|---|
| 1 | `migiq-deploy-coolstore-2-fetch-repository` | cloned branch `konveyor/migration-1793979085` at commit `70246f5` |
| 2 | `migiq-deploy-coolstore-2-build-image` | `mvn -B -ntp -DskipTests package` → `target/quarkus-app` → pushed `coolstore:migiq` (digest `sha256:1e7ce73…`) |
| 3 | `migiq-deploy-coolstore-2-deploy` | applied manifests, patched the Deployment image, `rollout status` green, result `route-url=https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com` |

### Deviation from the skill's default build task (cluster constraint)

The skill's `migiq-buildah` task needs `securityContext.privileged: true`. That is **not
admissible in this cluster** — the sandbox service account is rejected by every SCC that
allows privileged pods:

```
unable to validate against any security context constraint: [… privileged: Forbidden: not usable by user or serviceaccount]
```

Kaniko likewise cannot run (it requires UID 0, which `restricted-v2` forbids). The build
task was therefore implemented as an **unprivileged two-step build that reproduces the
Dockerfile's contract exactly**:

1. `registry.access.redhat.com/ubi9/openjdk-21:1.24` → the Dockerfile's build stage command
   (`mvn -B -ntp -DskipTests package` against the vendored `lib/maven-repo`), then packs
   `target/quarkus-app` as the `/deployments` layer;
2. `crane append` onto `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24` (the
   Dockerfile's runtime stage), then `crane mutate` to set the same runtime contract:
   `USER 185`, `WORKDIR /deployments`, entrypoint `java -jar /deployments/quarkus-run.jar`,
   `QUARKUS_HTTP_HOST=0.0.0.0`, `QUARKUS_HTTP_PORT=8080`, the JVM container flags, and the
   registry push to the internal registry.

The resulting image is byte-equivalent in behaviour to the Dockerfile (same base, same
layout, same user/entrypoint); only the packaging tool differs. The UBI image tags are
`1.24` (cluster-verified) instead of the Dockerfile's pinned `1.20`.

The deploy task was also hardened: manifests are applied **one file at a time** (the
skill template's `xargs … oc apply -f -` mis-passes filenames) and `secret.yaml` is
**never applied from git**, so placeholder credentials cannot overwrite the real Secret.

## 2. Manifests applied

Applied from the repository root by the pipeline's deploy task (`manifestsDir=.`), all via
the pipeline service account:

| Manifest | Objects | Result |
|---|---|---|
| `serviceaccount.yaml` | `ServiceAccount/coolstore` | unchanged |
| `configmap.yaml` | `ConfigMap/coolstore-config` (+ `coolstore.json`, `keycloak.json` keys) | configured |
| `postgresql.yaml` | `Service/postgresql`, `StatefulSet/postgresql` (`postgresql-0`, 10 Gi PVC `data-postgresql-0`) | created |
| `redis.yaml` | `Service/redis`, `Deployment/redis` | unchanged (applied ahead of the run) |
| `artemis.yaml` | `Service/amq-broker`, `Deployment/amq-broker` | unchanged (applied ahead of the run) |
| `deployment.yaml` | `Deployment/coolstore` (2 replicas, probes, restricted SCC context) | configured + image patched to `:migiq` |
| `service.yaml` | `Service/coolstore` (8080/http) | unchanged |
| `route.yaml` | `Route/coolstore` (edge TLS, redirect) | configured |
| `hpa.yaml` | `HorizontalPodAutoscaler/coolstore` (2–6, CPU 70 %) | configured |
| `pdb.yaml` | `PodDisruptionBudget/coolstore` (minAvailable 1) | created |
| `networkpolicy.yaml` | `NetworkPolicy/coolstore` (ingress from router/monitoring, egress to deps + DNS) | configured |
| `secret.yaml` | `Secret/coolstore-secrets` | **not applied from git** — created out of band with generated credentials (DB_USERNAME, DB_PASSWORD, ARTEMIS_USER, ARTEMIS_PASSWORD) |
| `kustomization.yaml` | — | not applied (files are applied individually) |

Notes:

* `secret.yaml` is a placeholder template; the live Secret was created with 24-character
  generated passwords. The `REDIS_PASSWORD` key was omitted rather than set to an empty
  string: the UBI `rhel9/redis-7` image rejects an empty `REDIS_PASSWORD` env var
  (`error: Invalid password`). With the key absent, the optional `secretKeyRef` resolves to
  "unset" and Redis runs without AUTH — the documented behaviour of the manifest
  ("empty = Redis without AUTH"), with access still restricted by `NetworkPolicy`.
* A temporary `Deployment/postgresql` shim (StatefulSet is not creatable by the *agent's*
  service account, only by the pipeline's deployer account) was used to bring the database
  up before the pipeline; once the authoritative `StatefulSet/postgresql` became ready the
  shim and its PVC were deleted and the app was restarted so Flyway migrated the surviving
  database. Only one PostgreSQL backend remains.

## 3. Dependencies

| Dependency | Status | Detail |
|---|---|---|
| PostgreSQL 15 | ✅ deployed | `StatefulSet/postgresql` + 10 Gi RWO PVC, `registry.redhat.io/rhel9/postgresql-15`; Flyway migrates the schema at startup; health check `UP` |
| Redis 7 | ✅ deployed | `Deployment/redis`, `registry.redhat.io/rhel9/redis-7`, ephemeral `emptyDir` cart cache, AUTH disabled; health check `PONG` |
| AMQ 7 / Artemis | ✅ deployed | `Deployment/amq-broker`, `quay.io/artemiscloud/activemq-artemis-broker:1.0.31`, JMS `topic/orders`; health check `UP` |
| Keycloak (OIDC) | ⏭️ skipped | Optional; `OIDC_ENABLED=false` in the ConfigMap (backend enforces no authorization — parity with the legacy app). Not shipped by the containerization stage. |
| Audit TCP collector | ⏭️ skipped | Optional; `AUDIT_STREAM_ENABLED=false` (emission is non-blocking, no collector shipped). |
| Kafka | ⏭️ not applicable | This variant of the app uses Artemis/JMS, not Kafka; no `kafka-topic.yaml` exists and no Strimzi operator is installed. |

## 4. Pod status

```
NAME                                            READY   STATUS      RESTARTS   AGE
amq-broker-7bdfbc854f-s2nsh                     1/1     Running     0          4m
coolstore-98f84444d-4748n                       1/1     Running     0          35s
coolstore-98f84444d-sf6vh                       1/1     Running     0          46s
postgresql-0                                    1/1     Running     0          2m
redis-546844f7c9-cfk4z                          1/1     Running     0          4m
migiq-deploy-coolstore-2-fetch-repository-pod   0/1     Completed   0          3m
migiq-deploy-coolstore-2-build-image-pod        0/2     Completed   0          3m
migiq-deploy-coolstore-2-deploy-pod             0/1     Completed   0          2m
```

`Deployment/coolstore`: 2/2 ready, image `…/coolstore-migiq/coolstore:migiq`,
`startupProbe`/`livenessProbe`/`readinessProbe` on `/q/health/{started,live,ready}` all green.

> Pre-existing leftovers from an earlier, superseded deployment of CoolStore in this same
> namespace (`Deployment/kafka`, `Deployment/postgres`, `Secret/coolstore-db`,
> `Secret/coolstore-broker`) were left untouched. They are **not** part of this repository's
> manifests and are not referenced by the new Deployment (its ConfigMap points at
> `postgresql:5432`, `redis:6379`, `amq-broker:61616`).

## 5. Route and HTTP verification

Route: `coolstore` → service `coolstore:http`, **edge TLS with redirect**
(`https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com`)

| Path | HTTP code | Notes |
|---|---|---|
| `/` | **200** | SPA served, ConfigMap-mounted `coolstore.json` wins over the classpath copy |
| `/q/health/ready` | **200** | `UP` — Database `UP`, Artemis JMS `UP`, Redis `PONG` |
| `/q/health/live` | **200** | `UP` |
| `/q/health/started` | **200** | `UP` |
| `/health` | 404 | not a Quarkus endpoint |
| `/healthz` | 404 | not a Quarkus endpoint |
| `/services/products` | **200** | catalog REST API |
| `/q/metrics` | **200** | Prometheus scrape endpoint |
| `/coolstore.json` | **200** | runtime SPA configuration from the ConfigMap |
| `POST /services/cart/smoke-1/329299/2` | **200** | cart write exercised **Redis** |
| `GET /services/cart/smoke-1` | **200** | totals computed (`cartTotal 17.99`, promo −5.00) |
| `POST /services/cart/checkout/smoke-1` | **200** | checkout exercised **PostgreSQL** (order persisted) + **Artemis** (`topic/orders`) |
| `GET /services/orders/` | **200** | persisted orders returned (e.g. `orderId: 1, orderValue: 17.99`) |

Endpoint paths were derived from the source (`@Path("/products")`, `@Path("/cart")`,
`@Path("/orders")` with `quarkus.rest.path=/services`, plus the probes in `deployment.yaml`)
and cross-checked against `docs/runbook.md`. No rgctl graph index (`.rgctl/`) was present in
this workspace, so no graph query was needed to identify them.

## 6. Follow-ups (production hardening)

1. Replace the reference PostgreSQL/Redis/Artemis Deployments with operators or managed
   services; the StatefulSet database is single-replica with no backups.
2. Replace the generated Secret with External Secrets / Vault; rotate the DB and broker
   passwords used for this smoke deployment.
3. For a fully reproducible image, grant the build service account the `privileged` SCC (or
   add a buildah-capable SCC) so the pipeline can build the Dockerfile verbatim instead of
   the Maven + crane assembly used here.
4. Enable Keycloak (`/auth/` route, realm `eap`) and the audit collector only if those
   features are required; both are disabled by default for behavioural parity.
