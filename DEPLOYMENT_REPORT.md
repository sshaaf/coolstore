# Deployment Report — CoolStore Monolith on OpenShift

**Application**: `coolstore` (Quarkus 3.27.6 / Java 21, CoolStore storefront + `/services` API)
**Namespace**: `coolstore-migiq`
**Cluster API**: `https://172.30.0.1:443` (in-cluster service-account endpoint of the sandbox cluster;
application domain `apps.ocp.g5pzs.sandbox1052.opentlc.com`)
**Deployed**: 2026-11-06 05:25 UTC
**Status**: ✅ PipelineRun `Succeeded` — 2/2 replicas Ready, Route serving HTTP 200 on the SPA, all
`/q/health/*` probes UP (database **and** both Kafka channels), and a full cart → checkout → Kafka →
order-persisted flow executed successfully.

---

## 1. Result at a glance

| | |
|---|---|
| Application URL | **https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com** |
| Route | `coolstore` — edge TLS, `insecureEdgeTerminationPolicy: Redirect`, `balance: source` |
| Image | `image-registry.openshift-image-registry.svc:5000/coolstore-migiq/coolstore:migiq` |
| Image digest | `sha256:37f762c1f88ee95d9d9f2a0de56c1de6214536305b8406a5e06cde97f9acd9a8` |
| Tekton PipelineRun | `migiq-deploy-coolstore` — **Succeeded** (start 05:24:09Z, complete 05:25:30Z) |
| Source revision built | `1791933` on `konveyor/migiq-multistage` (`https://github.com/sshaaf/coolstore.git`) |
| Replicas | 2/2 Ready (`coolstore-65bbc785fc-bn45j`, `coolstore-65bbc785fc-6j4gv`) |
| Health | `/q/health/ready` = 200, status **UP** (DB + `orders`/`orders-out` channels) |

---

## 2. Build and deploy pipeline

`/tmp/migiq-pipeline.yaml` (from `references/pipeline.yaml`, adapted — see §2.1) was applied to
`coolstore-migiq` and executed as PipelineRun `migiq-deploy-coolstore`.

| Tekton task | Result | Duration |
|---|---|---|
| `migiq-git-clone` | Succeeded — fetched `konveyor/migiq-multistage` @ `1791933` into the 4 GiB workspace PVC | ~10 s |
| `migiq-image-build` | Succeeded — Maven package + unprivileged image assembly, pushed to the internal registry | ~28 s (Maven) |
| `migiq-deploy` | Succeeded — 7 manifests applied, Deployment patched to the built image, rollout complete, route URL exported | ~27 s |

### 2.1 Deviation: how the image is built (SCC constraint)

The sandbox service account is bound only to `restricted-v2`, `restricted-v3` and `pipelines-scc`,
and **`privileged: true` is rejected by all of them**:

```
provider pipelines-scc: .containers[0].privileged: Invalid value: true: Privileged containers are not allowed
```

`pipelines-scc` also refuses to add *any* capability, and the agent pod has an all-zero capability
set. Consequences, each verified on this cluster:

| Builder | Result |
|---|---|
| `buildah` (reference implementation) | Step pod rejected at admission (privileged). Unprivileged retry: `error writing "0 0 4294967295" to /proc/<pid>/uid_map: operation not permitted` (no user namespaces, no `CAP_SYS_CHROOT`/`CAP_SYS_ADMIN`). |
| `kaniko` | Runs unprivileged and pushes, but cannot unpack the UBI base image: `failed to write "security.capability" attribute to "/usr/bin/newgidmap": operation not permitted` (no `CAP_SETFCAP`, which may not be added). |

The build therefore still happens **inside Tekton**, with a two-step, fully unprivileged assembly:

1. **`maven-package`** — `registry.access.redhat.com/ubi9/openjdk-21:1.24`, runs the same Maven
   command as the Dockerfile build stage (`mvn -B -ntp -DskipTests package`), then tars
   `target/quarkus-app` with a `/deployments` prefix (`layer.tar`, 86 MB).
2. **`assemble-and-push`** — `crane` appends that layer to the Dockerfile's runtime base
   `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24` and sets the runtime contract on the
   image config: `ENTRYPOINT ["java","-jar","/deployments/quarkus-run.jar"]`, `WORKDIR /deployments`,
   `USER 185`, and `HOME=/tmp`, `LANG=C.UTF-8`, `QUARKUS_HTTP_PORT=8080`, `JAVA_TOOL_OPTIONS=…`.
   The push authenticates with the pod's service-account token and trusts the internal registry via
   the injected `openshift-service-ca.crt` bundle.

The resulting image is functionally identical to what `podman build -f Dockerfile` produces
(identical base, identical fast-jar layout, identical entrypoint/env/user). It does **not** carry the
Dockerfile's `LABEL`s or its `HEALTHCHECK` (OpenShift ignores `HEALTHCHECK` anyway and uses the
startup/readiness/liveness probes from `deployment.yaml`).

---

## 3. Manifests applied

`MANIFESTS_DIR=deploy/base` — the byte-identical kustomize mirror of the repository-root manifests
(they differ only by a header comment), because the root directory also contains `secret.yaml` whose
values are placeholders. The pipeline applies, in namespace `coolstore-migiq`:

| File | Object |
|---|---|
| `deploy/base/serviceaccount.yaml` | ServiceAccount `coolstore` (token not auto-mounted) |
| `deploy/base/configmap.yaml` | ConfigMap `coolstore-config` (24 non-secret runtime keys) |
| `deploy/base/deployment.yaml` | Deployment `coolstore` — 2 replicas, `maxUnavailable: 0`, RO rootfs, `emptyDir` on `/tmp`, probes `/q/health/{started,ready,live}` |
| `deploy/base/service.yaml` | Service `coolstore` (ClusterIP, port `http` 8080) |
| `deploy/base/route.yaml` | Route `coolstore` (edge TLS + redirect, source-IP balancing) |
| `deploy/base/networkpolicy.yaml` | NetworkPolicy `coolstore` (ingress from routers only; egress DNS, 5432, 9092, 443, 9000) |
| `deploy/base/hpa.yaml` | HorizontalPodAutoscaler `coolstore` (2–6, 70 % CPU) |

Created **out of band** (never from the repository — `secret.yaml` holds placeholders only):

```bash
oc create secret generic coolstore-db     -n coolstore-migiq \
  --from-literal=url=jdbc:postgresql://postgres:5432/postgresDB \
  --from-literal=username=postgresUser \
  --from-literal=password=<random 32-hex>
oc create secret generic coolstore-broker -n coolstore-migiq \
  --from-literal=bootstrap-servers=kafka-bootstrap:9092
```

`coolstore-oidc` was not created — `OIDC_ENABLED=false`, and `deployment.yaml` marks that
`secretKeyRef` `optional: true`.

---

## 4. External dependencies

`CONTAINERIZATION_REPORT.md` §2.3 lists four external services. This cluster provides none of them
in the target namespace, and the application fails fast without a database and reports **DOWN** in
`/q/health/ready` without a broker (`mp.messaging.*.health-enabled=true`), so both were provisioned
as verification-only workloads in `coolstore-migiq` (`/tmp/deps.yaml`, **not** part of the
repository, ephemeral `emptyDir` storage):

| Dependency | Status | How / why |
|---|---|---|
| PostgreSQL | **deployed** (verification only) | `quay.io/sclorg/postgresql-16-c9s:latest`, Deployment + Service `postgres:5432`, database `postgresDB`, user `postgresUser`, password from Secret `coolstore-db`. Flyway `V1_1`/`V1_2` migrated on first boot and the catalogue seed data is visible through the API. The canonical target remains a managed/shared PostgreSQL (no StatefulSet is shipped, per the containerization report). |
| Kafka / AMQ Streams | **deployed** (verification only) | `bitnamilegacy/kafka:3.9.0`, single-node KRaft (`broker,controller`) behind Service `kafka-bootstrap:9092`. The `apache/kafka` image could not be used: it requires a writable `/opt/kafka/config`, which `restricted-v2` does not permit. Topic `orders` is auto-created; producer and consumer both report `[OK]` in the readiness check and a checkout message was consumed end-to-end. Production target remains AMQ Streams. |
| Keycloak | **skipped** | `OIDC_ENABLED=false` (backend bearer protection is opt-in, per `MIGRATION.md`); the SPA's `/services/config/keycloak.json` is generated from configuration and does not contact Keycloak at startup. A Keycloak instance does exist in the cluster (`keycloak` namespace, route `sso.apps.ocp.g5pzs.sandbox1052.opentlc.com`); enabling SSO would only require setting `OIDC_*` in the ConfigMap and importing `realm-export.json`. |
| Audit collector (`audit-collector:9000` NDJSON/TCP) | **skipped** | No collector in the cluster. The ConfigMap keeps `AUDIT_ENABLED=true` exactly as committed; the audit logger is asynchronous and a failed delivery only logs `Audit event for order 1 could not be delivered: Failed to send audit event over TCP` — checkout still succeeds, as documented. Setting `AUDIT_ENABLED=false` is the switch for environments without a collector. |
| `kafka-topic.yaml` | **skipped** | No such file in the repository; the `orders` topic is auto-created by the broker. |

---

## 5. Runtime verification

### 5.1 Pods

```
NAME                         READY   STATUS    RESTARTS   AGE
coolstore-65bbc785fc-bn45j   1/1     Running   0          3m
coolstore-65bbc785fc-6j4gv   1/1     Running   0          3m
kafka-58dbcdd4f-zf6wt        1/1     Running   0          28m
postgres-577d684fbb-v5s4l    1/1     Running   0          31m
```

`oc rollout status deployment/coolstore` → `deployment "coolstore" successfully rolled out` (2/2,
`maxUnavailable: 0`). Startup probe failed once with `connection refused` while the JVM booted, then
passed; liveness/readiness have not failed since. Pods run under `restricted-v2` with no explicit
`runAsUser`, `readOnlyRootFilesystem: true`, all capabilities dropped and no SA token.

### 5.2 HTTP checks on the Route

`https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com`

| Path | Code | Notes |
|---|---|---|
| `/` | 200 | AngularJS SPA — `<title>Red Hat Cool Store MSA - Monolith</title>` |
| `/q/health/started` | 200 | UP |
| `/q/health/live` | 200 | UP |
| `/q/health/ready` | 200 | UP — `SmallRye Reactive Messaging - readiness check` (`orders`, `orders-out` = `[OK]`) and `Database connections health check` = UP |
| `/q/health` | 200 | aggregate UP |
| `/q/metrics` | 200 | Prometheus/OpenMetrics output |
| `/services/products` | 200 | 6 seeded products from the migrated schema |
| `/services/products/329299` | 200 | `Quarkus T-shirt` |
| `/services/cart/smoke-2` | 200 | empty cart for a new `cartId` |
| `/services/orders` | 200 | order created by the smoke test |
| `/services/config/coolstore.json` | 200 | SPA bootstrap document generated at runtime |

### 5.3 Functional smoke test (cart → Kafka → order)

```bash
POST /services/cart/smoke-cart/329299/2      -> 200, cartTotal 17.99
POST /services/cart/checkout/smoke-cart      -> 200, cart returned and emptied
GET  /services/orders                        -> 200
[{"orderId":1,"customerName":"Sven Karlsson","orderValue":17.99,...,
  "itemList":[{"quantity":2,"productId":"329299"}]}]
```

Application log confirms the full path: `Sending order …` → `SRMSG18256: Initialize record store for
topic-partition 'orders-0'` → `OrderConsumer - Message recd !` → order persisted (readable through
`/services/orders`). This exercises Hibernate ORM + Flyway (PostgreSQL), the SmallRye Kafka producer
and consumer, and the JAX-RS layer on the migrated Quarkus runtime.

---

## 6. Observations and follow-ups

1. **HPA cannot scale here** — `FailedGetResourceMetric: no metrics returned from resource metrics
   API`; the sandbox has no user-workload metrics-server, so the HPA stays at `minReplicas: 2`.
   Deployments are unaffected.
2. **ImageStream trigger vs. pipeline tag** — `deployment.yaml` carries an
   `image.openshift.io/triggers` annotation for `ImageStreamTag coolstore:latest`, while the pipeline
   pushes tag `migiq`; one intermediate ReplicaSet tried to pull `coolstore:latest` from Docker Hub
   and failed. The PipelineRun patches the Deployment image explicitly, so the rollout converged.
   Recommendation: push an additional `latest` tag (or change the annotation to `coolstore:migiq`)
   before relying on image-triggered rollouts.
3. **Ephemeral dependencies** — PostgreSQL and Kafka are verification-only (`emptyDir`): a restart
   loses the schema/seed and the topic. Point `coolstore-db`/`coolstore-broker` at the real managed
   PostgreSQL and AMQ Streams before any durable use.
4. **`secret.yaml` was deliberately not applied** — placeholders must never become credentials; the
   secrets were created out of band, as `CONTAINERIZATION_REPORT.md` §6 requires.
5. **Audit collector absent** — non-fatal warnings on checkout; set `AUDIT_ENABLED=false` or deploy a
   collector at `audit-collector:9000`.
6. **Build mechanism** — if the platform grants `privileged` (or `CAP_SETFCAP`) to the pipeline
   service account, the reference `buildah` task can be restored unchanged; the crane assembly is a
   workaround for this sandbox's SCC policy, not a recommendation for production pipelines.

## 7. Rollback

The database schema is unchanged (Flyway migrations are the EAP-era files, byte-identical), so
rollback is code-only:

```bash
oc rollout undo deployment/coolstore -n coolstore-migiq
oc rollout history deployment/coolstore -n coolstore-migiq
```
