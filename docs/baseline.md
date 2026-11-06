# Behavioural baseline (task group 1)

## What was frozen
The legacy application (`ROOT.war`, Java EE 7, JBoss EAP 7.4) was characterised at three
levels before any code changed:

1. **Business rules** - shipping brackets, insurance tiers, promotions, cart pricing,
   dedupe and the order-message JSON (unit tests, `mvn test`, 67 tests).
2. **HTTP contract** - 9 endpoint shapes with their status codes and payload field names,
   recorded under `src/test/resources/golden/` (see that directory's README).
3. **Performance envelope** - load profile and comparison method in `docs/perf-baseline.md`.

## Environment matrix (legacy reference)
| Component | Version | Purpose |
|---|---|---|
| JBoss EAP | 7.4 | servlet/EJB/JMS container, `ROOT.war` |
| OpenJDK | 17 (runtime), source level 8 | legacy build |
| PostgreSQL | 13+ | `PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS` |
| ActiveMQ Artemis | 2.x (EAP embedded or AMQ 7) | `topic/orders` |
| Keycloak | 20 (realm `eap`, client `eap-app`) | SPA login |
| Maven | 3.8+ | legacy WAR build |

Legacy runtime configuration: JNDI datasource `java:jboss/datasources/CoolstoreDS`, JMS
destination `java:/topic/orders`, JNDI EJB lookup `ejb:/ROOT/ShippingService!...`.

## Golden response inventory
| Fixture | Source |
|---|---|
| `golden/products.json` | `GET /services/products` (9 products, seed data from `V1_2`) |
| `golden/cart-after-add-329299-x2.json` | `POST /services/cart/{c}/329299/2` |
| `golden/order-message-2x329299.json` | `topic/orders` body produced at checkout |

Re-recording procedure against a legacy deployment:
```bash
mvn -B package && cp target/ROOT.war $JBOSS_HOME/standalone/deployments/
curl -s localhost:8080/services/products | jq . > src/test/resources/golden/products.json
curl -s localhost:8080/services/cart/demo | jq . > /tmp/cart.json
```
Capture the JMS body with the broker console (`topic/orders` → browse queue) and store it
with `customerName`/`customerEmail` replaced by placeholders (they are random by design).

## Known legacy behaviour preserved on purpose (parity over correctness)
* `calculateShipping` returns `0` for totals ≥ 10000 and for negative totals.
* Shipping insurance is charged for 25 ≤ total < 10000 (2% / 1.5% / 1%) - not at 25 or above.
* `priceShoppingCart` recomputes from the catalog on every call (client-supplied prices are
  ignored) and re-applies promotions, so it is idempotent.
* An unknown product on add produces a 500 (NPE/`IllegalArgumentException` path).
* `customerName`/`customerEmail` are picked at random from a six-entry list per checkout.
* `GET /services/products/{unknown}` returns 204 No Content.
