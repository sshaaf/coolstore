# Baseline (Group 1)

## Toolchain decision

Three conflicting Java levels existed: `pom.xml` `source/target 1.8`, README "OpenJDK 17", CI
workflow "Java 11". The migration pins **one** level everywhere: Java 21 (`maven.compiler.release`),
which is mandatory for `audit-logging-library` 2.0.0 (`record AuditEvent`, virtual threads).
Baseline build verified with Temurin 21.0.13 + Maven 3.9.9.

## Reproducible build

| Item | Before | After |
|---|---|---|
| Artefact | `target/ROOT.war` | `target/quarkus-app/` (fast-jar, `quarkus-run.jar`), ~86 MB un-shrunk/build-dir, image layer far smaller |
| Packaging | `war` | `jar` |
| Tests | skipped (`maven.test.skip=true`) | 84 tests execute on every build and fail the build |
| Checksum recording | `mvn -B clean package` + record `sha256sum target/ROOT.war` | `mvn -B verify` + record sha256 of `quarkus-app-dependencies.txt` and the image digest |

> No JBoss EAP 7.4 instance, PostgreSQL or Keycloak is reachable from the execution environment, so
> the WAR checksum and the p95/throughput numbers could **not** be re-measured here. The baseline is
> instead encoded executably (below) and the performance targets are carried into `docs/openshift.md`
> as an acceptance gate to run on the reference environment (Story 1 tasks 1.6/1.9/1.10 and Story 9
> task 9.2).

## Endpoint inventory (9 JAX-RS methods, unchanged paths)

| # | Method | Path | Resource |
|---|---|---|---|
| 1 | GET | `/services/products` | `ProductEndpoint.listAll` |
| 2 | GET | `/services/products/{itemId}` | `ProductEndpoint.getProduct` |
| 3 | GET | `/services/cart/{cartId}` | `CartEndpoint.getCart` |
| 4 | POST | `/services/cart/{cartId}/{itemId}/{quantity}` | `CartEndpoint.add` |
| 5 | POST | `/services/cart/{cartId}/{tmpId}` | `CartEndpoint.set` (merge) |
| 6 | DELETE | `/services/cart/{cartId}/{itemId}/{quantity}` | `CartEndpoint.delete` |
| 7 | POST | `/services/cart/checkout/{cartId}` | `CartEndpoint.checkout` |
| 8 | GET | `/services/orders` | `OrderEndpoint.listAll` |
| 9 | GET | `/services/orders/{orderId}` | `OrderEndpoint.getOrder` |

## Pricing tables (pinned by `ShippingServiceTest`, `PricingRulesTest`)

| Cart item total | Shipping |
|---|---|
| 0 – <25 | 2.99 |
| 25 – <50 | 4.99 |
| 50 – <75 | 6.99 |
| 75 – <100 | 8.99 |
| 100 – <10000 | 10.99 |
| ≥10000 | 0 |

Insurance: 25–<100 → 2 %, 100–<500 → 1.5 %, 500–<10000 → 1 %, `BigDecimal.HALF_UP` at scale 2,
only applied when `cartItemTotal >= 25`. Promotions: item `329299` −25 %, free shipping from 75.

## Checkout message contract (pinned by `TransformersTest` golden documents)

```json
{"orderValue":22.99,"customerName":"Sven Karlsson","customerEmail":"sven@gmail.com",
 "retailPrice":20.0,"discount":0.0,"shippingFee":2.99,"shippingDiscount":0.0,
 "items":[{"productSku":"329299","quantity":2}]}
```

Key order, numeric formatting (`20.0`, not `20`) and the randomized name/e-mail pair are asserted.

## Flyway behaviour (pinned by `PersistenceContractTest`)

Empty database → V1.1 + V1.2 applied, 9 `PRODUCT_CATALOG` and 9 `INVENTORY` rows. Already-migrated
database → `migrate()` executes 0 migrations, `validateWithResult()` succeeds (no checksum error).

## Executable baseline suite (84 tests)

`ShippingServiceTest` (28) · `PromoServiceTest` (5) · `PricingRulesTest` (6) · `TransformersTest` (10) ·
`RestContractTest` (11) · `PersistenceContractTest` (5) · `PersistenceSmokeTest` (1) ·
`OrderMessagingTest` (5) · `AuditLoggingTest` (6) · `MigrationGuardrailsTest` (7).
