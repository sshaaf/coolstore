# Test strategy

| Layer | Location | Runs in | Needs |
| --- | --- | --- | --- |
| Unit (no container) | `src/test/java/**`, classes ending in `Test` | `mvn test` (Surefire) | JDK 21 only |
| Integration (`@QuarkusTest`) | classes ending in `IT` | `mvn verify` (Failsafe) | Docker/Podman for Dev Services PostgreSQL |
| Migration safety | `persistence/FlywayMigrationIT` | `mvn verify` | Docker/Podman (Testcontainers `postgres:15`) |
| Storefront E2E | out of scope in code, see `docs/runbook.md` smoke commands | pipeline | deployed route |

## What is covered

- **Pricing parity:** `ShippingServiceTest` (every band boundary), `PromoServiceTest` (25% item promotion,
  free shipping at 75), `ShoppingCartServiceTest` (`priceShoppingCart` totals, idempotency).
- **Cart lifecycle:** add/merge, partial and full delete, anonymous cart transfer, two-cart isolation,
  checkout clearing and single-event publication.
- **Wire contract:** `TransformersTest` asserts the exact JSON string of an order event, the key order, the
  empty-cart case, multi-item carts and the `jsonToOrder` round trip.
- **Audit:** `OrderServiceAuditTest` asserts the emitted record's 14 components, action/resource/result, the
  failure variant, and that the record really has 14 components.
- **Low-stock rule:** `InventoryNotificationConsumerTest` (threshold, malformed payload, empty items).
- **Static guards:** `StaticGuardsTest` fails the build if `javax.*` imports, `weblogic`, JBoss JNDI names,
  `InitialContext` or `PortableRemoteObject` reappear, or if legacy files are restored.
- **REST contract:** `ProductEndpointIT`, `CartEndpointIT`, `HealthIT`, `StorefrontIT`.
- **Persistence:** `OrderPersistenceIT` (entity/id/FK round trip), `FlywayMigrationIT` (migrations on a fresh
  PostgreSQL 15 + seed row counts + idempotent re-run).
- **Messaging:** `CheckoutFlowIT` (checkout -> emitter -> consumer -> order row + inventory decrement).

## Test profile

`%test` in `application.properties`:

- `quarkus.datasource.devservices.enabled=true` with `postgres:15` (Testcontainers under the hood)
- `quarkus.flyway.migrate-at-start=true` so the real migrations seed the schema
- messaging connectors switched to `smallrye-in-memory` (no broker in CI)
- `audit.enabled=false` (no collector in CI)
- `coolstore.sso-enabled=false`

## Known gaps

- No browser-driven E2E test for the AngularJS SPA (documented as out of scope; curl smoke tests in the
  runbook cover the API journey).
- `quarkus-oidc` is not enabled, so no authenticated-backend tests exist.
- Performance baselines (p95, boot time) must be captured against the deployed service; they cannot be
  asserted in a unit/CI test without a load generator.
