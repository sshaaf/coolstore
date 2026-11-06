# Operations runbook - CoolStore on OpenShift

## 1. Prerequisites

| Dependency | Version | Notes |
| --- | --- | --- |
| PostgreSQL | 15+ | schema and seed data created by Flyway at startup |
| Artemis / AMQP 1.0 broker | 2.x | address `orders` with **multicast** (topic) semantics so both consumers receive each event |
| Audit collector | TCP | receives one JSON `AuditEvent` per connection |
| Keycloak (optional) | 20+ | realm `eap`; only needed when `SSO_ENABLED=true` |

## 2. Build and push the image

```bash
mvn -B clean verify                 # unit + integration tests, target/quarkus-app/
podman build -t coolstore:$(git rev-parse --short HEAD) .
podman push coolstore:$(git rev-parse --short HEAD) \
  image-registry.openshift-image-registry.svc:5000/coolstore/coolstore:$(git rev-parse --short HEAD)
```

Jib alternative (no local container engine, pushes directly):

```bash
mvn -B package -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=image-registry.openshift-image-registry.svc:5000 \
  -Dquarkus.container-image.group=coolstore \
  -Dquarkus.container-image.tag=$(git rev-parse --short HEAD)
```

Image scanning (fail threshold: no fixable CRITICAL):

```bash
trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed coolstore:local
```

## 3. First deployment

```bash
oc new-project coolstore
oc -n coolstore create secret generic coolstore-db \
  --from-literal=QUARKUS_DATASOURCE_PASSWORD='<password>'
oc -n coolstore create secret generic coolstore-broker \
  --from-literal=AMQP_PASSWORD='<password>'
# edit deploy/configmap.yaml for this environment (DB URL, broker host, audit host, Keycloak URL)
oc apply -k deploy/
oc -n coolstore rollout status deployment/coolstore
oc -n coolstore get route coolstore
```

Smoke test:

```bash
ROUTE=$(oc -n coolstore get route coolstore -o jsonpath='{.spec.host}')
curl -fsS "https://$ROUTE/q/health/ready"
curl -fsS "https://$ROUTE/services/products" | head -c 200
curl -fsS -X POST "https://$ROUTE/services/cart/demo/329299/1"
curl -fsS -X POST "https://$ROUTE/services/cart/checkout/demo"
```

## 4. Configuration reference

| ConfigMap key | Secret key | Effect |
| --- | --- | --- |
| `QUARKUS_DATASOURCE_JDBC_URL` | - | JDBC URL |
| `QUARKUS_DATASOURCE_USERNAME` | `QUARKUS_DATASOURCE_PASSWORD` | datasource credentials |
| `AMQP_HOST`, `AMQP_PORT`, `AMQP_USER` | `AMQP_PASSWORD` | broker |
| `AUDIT_STREAM_HOST/PORT/PROTOCOL` | - | audit collector |
| `SSO_ENABLED`, `KEYCLOAK_AUTH_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_RESOURCE` | `KEYCLOAK_CLIENT_SECRET` (if confidential) | storefront SSO |
| `CART_TTL_SECONDS` | - | idle cart eviction |
| `JAVA_OPTS_APPEND` | - | JVM flags (`-XX:MaxRAMPercentage=75`) |

Change a value: `oc -n coolstore edit configmap coolstore-config` then
`oc -n coolstore rollout restart deployment/coolstore`.

## 5. Scaling and cart state

The default `CartStore` is in-memory and **per pod**. With `replicas > 1` either

- enable sticky sessions on the route (`haproxy.router.openshift.io/balance: source` plus affinity), or
- provide a Redis-backed `CartStore` implementation (`quarkus-redis-client`) and set `CART_STORE=redis`.

`deploy/hpa.yaml` caps at 4 replicas; the PodDisruptionBudget keeps at least one pod available.

## 6. Logs, metrics, health

```bash
oc -n coolstore logs deployment/coolstore -f
oc -n coolstore exec deploy/coolstore -- curl -s localhost:8080/q/health/ready
oc -n coolstore exec deploy/coolstore -- curl -s localhost:8080/q/metrics | grep http_server
```

- `/q/health/live` - liveness, no dependencies.
- `/q/health/ready` - readiness, includes a datasource check (`database`).
- `/q/metrics` - Prometheus. Suggested alert: 5xx rate > 1% over 5 minutes.

## 7. Rollback

Flyway migrations are **forward-only**, so the database needs its own rollback:

```bash
# 1. application rollback
oc -n coolstore rollout undo deployment/coolstore
oc -n coolstore rollout status deployment/coolstore

# 2. database rollback (only if the release changed the schema)
oc -n coolstore exec deploy/postgres -- \
  pg_dump -U coolstore coolstore > /tmp/coolstore-$(date +%F).sql   # snapshot before every release
oc -n coolstore exec -i deploy/postgres -- \
  psql -U coolstore -d coolstore < /tmp/coolstore-<snapshot>.sql
```

Pin image tags per release (`image: .../coolstore:<git-sha>`) so `rollout undo` always has a previous
revision. Rehearse this in a scratch namespace before every production cutover.

## 8. Troubleshooting

| Symptom | Check |
| --- | --- |
| readiness `DOWN`, `database` check failing | Secret password, `QUARKUS_DATASOURCE_JDBC_URL`, NetworkPolicy egress to 5432 |
| orders never appear in `/services/orders` | broker reachable (`AMQP_*`), address `orders` exists with multicast semantics, `oc logs` for `OrderEventConsumer` errors |
| checkout returns 200 but nothing is persisted | consumer log line `Could not process order event` (malformed payload) |
| carts disappear between requests | more than one replica with the in-memory store - see section 5 |
| `403`/`DOWN` on startup | image runs as UID 1001 with a read-only root filesystem; only `/tmp` (emptyDir) is writable |
| audit warnings in logs | collector unreachable; checkout still succeeds by design |
