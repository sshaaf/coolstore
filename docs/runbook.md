# Operations runbook

## Health
| Probe | Path | Meaning |
|---|---|---|
| startup | `/q/health/started` | JVM + CDI up; Flyway may still be running |
| liveness | `/q/health/live` | process healthy (no dependency checks) |
| readiness | `/q/health/ready` | DB (Agroal), Redis, Artemis reachable |

`readiness` returns 503 when any of PostgreSQL / Redis / Artemis is down; the pod is then
removed from the Service but not restarted.

## Degradation matrix
| Failure | Symptom | Behaviour | Action |
|---|---|---|---|
| PostgreSQL down | `/services/*` 500, readiness 503 | app stays up, no crash loop | restore DB; Flyway retries on next start |
| Redis down | cart endpoints 500 (`CartStoreException`) | catalog and orders unaffected | restore Redis; carts are ephemeral (TTL) |
| Broker down | checkout 500, no order row | publish happens before reset, so the cart is intact and retryable | restore broker, retry checkout |
| Audit collector down | warning logs, no events | checkout unaffected (non-blocking, readiness-gated) | restore collector; events are dropped by design |
| Keycloak down | SPA login fails | REST API unaffected (`OIDC_ENABLED` is only used for the SPA) | restore Keycloak |

## DLQ
Orders that fail processing are redelivered up to `max-delivery-attempts` (default 5,
broker-side) and then moved to `orders.dlq`. Inspect with the broker console, fix the cause,
then move messages back to `topic/orders`.

## Rollback (≤ 15 min)
1. `oc rollout undo deployment/coolstore` (previous Quarkus image), or
2. repoint the Route to the legacy JBoss service: `oc patch route coolstore -p '{"spec":{"to":{"name":"legacy-coolstore"}}}'`.
3. Verify `GET /services/products` and cart/order availability.
Cart state is Redis-only, so a rollback to the legacy WAR loses carts created after cutover
by design; orders already persisted in PostgreSQL are unaffected.

## Configuration
All runtime config is `ConfigMap`/`Secret`-driven (see `deploy/openshift/base/`). Changing
`DB_PASSWORD` triggers a rolling restart; no rebuild required.
