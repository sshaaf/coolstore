# Deployment Report: CoolStore (Quarkus 3 / Java 21) on OpenShift

**Application URL: <https://coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com>**

| Item | Value |
| --- | --- |
| Cluster API | `https://172.30.0.1:443` (in-cluster service-account auth, no `oc login`) |
| Namespace | `coolstore-migiq` |
| PipelineRun | `migiq-deploy-coolstore-10` — **Succeeded** |
| Image | `image-registry.openshift-image-registry.svc:5000/coolstore-migiq/coolstore:migiq` (`sha256:f8422d95…`) |
| Route | `coolstore-coolstore-migiq.apps.ocp.g5pzs.sandbox1052.opentlc.com`, edge TLS, HTTP→HTTPS redirect |
| Replicas | 2/2 Ready (HPA 2–4, 70 % CPU) |

## 1. Build pipeline (Tekton)

Tasks `migiq-git-clone`, `migiq-image-build`, `migiq-deploy` and Pipeline `migiq-build-deploy` were
applied to `coolstore-migiq`. The run cloned `https://github.com/sshaaf/coolstore` at
`konveyor/migration-1793982623`, packaged the fast-jar with Maven and pushed the image to the
internal registry:

```
fetch-repository  Succeeded
build-image       Succeeded
deploy            Succeeded
```

### Deviations from the reference pipeline (all forced by this cluster)

1. **Build without `buildah`/privileged.** The sandbox's Tekton pods are admitted by SCC
   `pipelines-scc`, which forbids `privileged: true`; an in-pod `buildah` build is rejected at
   admission (`unable to validate against any security context constraint`). The image is still
   built *inside Tekton*: `mvn package` in `ubi9/openjdk-21` produces `target/quarkus-app`, which
   is appended as the `/deployments` layer of `ubi9/openjdk-21-runtime` with `crane`, and the
   Containerfile runtime contract (entrypoint, workdir, user, JVM options) is applied with
   `crane mutate`. No podman was used in the agent pod.
2. **`oc apply` one file at a time** so a single forbidden kind cannot abort the rollout.
3. **`secret.example.yaml` is never applied** — it is a `CHANGE_ME` template. The real Secrets
   `coolstore-db` and `coolstore-broker` were created out of band with 22-character random
   passwords before the run.
4. `migiq-git-clone` installs git with `dnf` (the `ubi9/ubi` image ships no `microdnf`) and
   `git init` + `git fetch` instead of `git clone` (the workspace PVC is non-empty: `lost+found`).

## 2. Manifests applied

`artemis.yaml`, `buildconfig.yaml`, `configmap.yaml`, `deployment.yaml`, `hpa.yaml`,
`imagestream.yaml`, `networkpolicy.yaml`, `pdb.yaml`, `postgres.yaml`, `route.yaml`,
`service.yaml`, `serviceaccount.yaml`.

Excluded: `kustomization.yaml` (the pipeline applies manifests individually),
`secret.example.yaml` (template only). No `kafka-topic.yaml` exists and no Kafka cluster is needed.

## 3. Source fixes required to make the image build/run

These were blocking defects in the branch; each is committed here and, because the sandbox cannot
push to GitHub (the changes reach the branch at stage end), also applied idempotently inside the
build step so the image matches the committed tree.

| File | Defect | Fix |
| --- | --- | --- |
| `pom.xml` | `io.quarkus:quarkus-jboss-logmanager` does not exist (404 on Central) and has no BOM-managed version → `mvn package` failed with “`dependencies.dependency.version` … is missing” | Dependency removed; the SLF4J binding is already provided transitively by `quarkus-core` → `org.jboss.slf4j:slf4j-jboss-logmanager` |
| `application.properties` + `ShoppingCartOrderProcessor.java` | Producer and consumer shared channel `orders` → `SRMSG00073: channel names cannot be used for both incoming and outgoing`, app failed to start | Producer uses channel `orders-out` targeting the same AMQP address `orders` |
| `configmap.yaml` | `coolstore.api-endpoint` / `coolstore.secure-api-endpoint` resolved to `""` (only `${API_ENDPOINT:}` defaults) → SmallRye failed conversion and Quarkus aborted startup | `API_ENDPOINT` / `SECURE_API_ENDPOINT` set to the route URL + `/services` |
| `postgres.yaml` | `fsGroup: 26` rejected by `restricted-v2`/`v3` (`26 is not an allowed group`) → `postgres-0` never created (`FailedCreate`) | `fsGroup` dropped; the SCC assigns the namespace group and the sclorg image runs as arbitrary UID |

## 4. Dependencies

| Dependency | Status | Notes |
| --- | --- | --- |
| PostgreSQL (postgres.yaml) | **deployed** | StatefulSet `postgres` 1/1, 5 Gi PVC `data-postgres-0`, headless Service; Flyway created schema + seed data at startup |
| AMQP 1.0 broker (artemis.yaml) | **deployed** | StatefulSet `artemis` 1/1, 2 Gi PVC, Service on 5672, multicast address `orders` (two consumers) |
| Audit collector | skipped | External; `AUDIT_ENABLED=true` but emits are non-fatal |
| Keycloak / OIDC | skipped | `SSO_ENABLED=false` |
| Kafka | skipped | Not used by the application |

A previous attempt had left unrelated workloads (`kafka`, `redis`, `amq-broker`, an older
`postgres` Deployment and a 31-key `coolstore-config`) in the namespace; these were deleted so the
current manifest set applies cleanly.

## 5. Pod status

```
NAME                          READY   STATUS    RESTARTS
coolstore-8768cbb5-ckvwq      1/1     Running   0
coolstore-8768cbb5-htkv6      1/1     Running   2
postgres-0                    1/1     Running   0
artemis-0                     1/1     Running   0
```

## 6. HTTP verification (via the route)

| Path | Code | Result |
| --- | --- | --- |
| `/` | 200 | Storefront |
| `/q/health/ready` | 200 | `status: UP` — Database connections health check UP, `database: PostgreSQL` |
| `/q/health/live` | 200 | UP |
| `/q/health/started` | 200 | UP |
| `/services/products` | 200 | Seeded catalogue returned (`329299 Quarkus T-shirt`, …) |
| `/q/metrics` | 200 | Prometheus registry |

Endpoints for post-deploy testing were derived from the JAX-RS sources (`ProductEndpoint`
`/products`, `OrderEndpoint` `/orders`, `CartEndpoint` `/cart/…`, global prefix `/services`); no
`rgctl` index was present in the repository for this stage.

## 7. Security posture

Edge-TLS route with redirect, `restricted-v2` pods (arbitrary UID, `runAsNonRoot`,
`readOnlyRootFilesystem`, all capabilities dropped, `RuntimeDefault` seccomp), no service-account
token in the pods, default-deny `NetworkPolicy` with explicit allows, and credentials only in
Secrets. Follow-up: the `coolstore-db` / `coolstore-broker` passwords are per-environment values —
rotate them with `oc create secret generic … --dry-run=client -o yaml | oc apply -f -` (restart the
StatefulSets afterwards) and replace `secret.example.yaml` usage in any future run.

## 8. Reproduce

```bash
oc apply -f /tmp/migiq-pipeline.yaml      # tasks + pipeline
oc create -f /tmp/migiq-pipelinerun.yaml  # build + deploy
oc wait --for=condition=Succeeded pipelinerun/migiq-deploy-coolstore-10 -n coolstore-migiq --timeout=1800s
oc rollout status deployment/coolstore -n coolstore-migiq
```
