# OpenShift deployment (Group 8)

## Layout

```
/<manifest>.yaml        canonical: configmap, secret (placeholders), serviceaccount, deployment,
                        service, route, networkpolicy, hpa, kustomization.yaml  (oc apply -k .)
deploy/base/            kustomize base - mirrors of the root manifests + kustomization.yaml
deploy/overlays/dev/    namespace coolstore-dev, image tag dev, 1 replica, reduced memory limits
deploy/overlays/prod/   namespace coolstore-prod, image tag 1.0.0, 3 replicas
```

Kustomize cannot load files above its own directory, so `deploy/base` carries copies of the root
manifests; the root files are canonical and both entry points apply the same objects.

```bash
# secrets first (placeholders in secret.yaml must be replaced, or create them with oc)
oc create secret generic coolstore-db -n coolstore-dev \
  --from-literal=url=jdbc:postgresql://postgres:5432/postgresDB \
  --from-literal=username=postgresUser --from-literal=password='<real>'
oc create secret generic coolstore-broker -n coolstore-dev \
  --from-literal=bootstrap-servers=coolstore-kafka-bootstrap:9092

oc apply -k deploy/overlays/dev      # or: oc apply -k .  in the target project
oc get pods -n coolstore-dev -w
curl -k https://$(oc get route coolstore -n coolstore-dev -o jsonpath='{.spec.host}')/q/health/ready
```

Secrets are not part of either kustomization on purpose: re-applying a placeholder would overwrite
real credentials.

## Probes and lifecycle

| Probe | Path | Purpose |
|---|---|---|
| `startupProbe` | `/q/health/started` | allows Flyway migration time before liveness starts |
| `readinessProbe` | `/q/health/ready` | includes DB (and broker) health; DOWN without restarts |
| `livenessProbe` | `/q/health/live` | JVM liveness only |

Rolling update `maxUnavailable: 0`, `maxSurge: 1`, `terminationGracePeriodSeconds: 45` and
`quarkus.shutdown.timeout=30s` so an in-flight checkout and the consumer finish. There is no
`preStop` hook: the runtime image (ubi9-micro based) has no shell; the JVM handles SIGTERM itself.

## Dependencies

| Dependency | Provisioning |
|---|---|
| PostgreSQL | in-cluster operator/StatefulSet or managed; database `postgresDB`, user `postgresUser` (Secret `coolstore-db`); Flyway migrates at start |
| Broker | AMQ Streams (Kafka) with topic `orders`, Secret `coolstore-broker` (`bootstrap-servers`); consumer group `coolstore-order-service` so both replicas consume (competing consumers) |
| Keycloak | realm `eap` from `realm-export.json`, client `eap-app`, redirect URIs = Route hostname |
| Audit collector | TCP `AUDIT_STREAM_HOST:AUDIT_STREAM_PORT`; NetworkPolicy egress 9000 |

NetworkPolicy: ingress 8080 from the ingress routers only; egress cluster DNS (53/5353),
5432 (PostgreSQL), 9092 (Kafka), 443 (Keycloak) and 9000 (collector).

## Pipeline

CI/CD is the platform-provided OpenShift Pipelines (Tekton) pipeline: build → `mvn verify` →
image build/push → manifest update. `.github/workflows/` is **not** modified (a pushed workflow file
is rejected by the platform); `MIGRATION.md` records the legacy Azure Web App workflow as obsolete.

## Verification (run on a real cluster)

1. Fresh namespace → pods Ready, Route serves the SPA, `/q/health/ready` UP.
2. Kill the PostgreSQL pod → readiness DOWN, recovery without manual restart.
3. Rolling update under continuous checkout → zero failed requests, no duplicate orders.
4. 2 replicas both consume from `orders`; consumer lag returns to 0.
5. Performance gate (Story 1 baseline): p95 for `/services/products` and checkout within 10 % of the
   JBoss baseline; pod RSS and startup below the EAP footprint.
6. Rollback drill: switch the Route back to JBoss EAP 7.4 with `ROOT.war` — no schema change required.

> Items 1–6 require a cluster, PostgreSQL, Kafka and Keycloak. They could not be executed in this
> migration environment; they remain the deployment-stage acceptance gate.
