# User Stories

**Migration**: CoolStore Monolith — Java EE 7 / JBoss EAP 7.4 → Quarkus 3.20 (Jakarta EE 10) / Java 21 on OpenShift
**Plan**: `mig-plan-workspace/tasks.md` · **Specification & design**: `mig-prompt-workspace/migration-prompt.md`

Ordering follows the rgctl Hybrid-Default roadmap: REST entry points first (rank 1), cross-cutting utilities (rank 3),
services (rank 5), model (rank 7), with foundation work pulled forward because every other story depends on it.

## User Story 1: Quarkus build foundation

**As a:** Platform Engineer
**I want:** The project to build as a Quarkus 3.20 / Java 21 application instead of a Java EE WAR
**So that:** The team has a runnable, supported target platform before any business code moves

---

### Tasks
- ## Task Group 1: Build foundation and project skeleton (from tasks.md)

### Acceptance Criteria
- [ ] `pom.xml` declares the Quarkus 3.20 BOM, `maven.compiler.release=21`, `jar` packaging and the Quarkus Maven plugin
- [ ] Java EE dependencies (`javaee-api`, `javaee-web-api`, `jboss-jms-api_2.0_spec`, `jboss-rmi-api_1.0_spec`) are gone
- [ ] The audit dependency resolves `lib/audit-logging-library-2.0.0.jar` instead of 1.0.0
- [ ] `web.xml`, `beans.xml`, `persistence.xml`, `weblogic/**`, `StartupListener` are deleted
- [ ] `application.properties` carries datasource, ORM, Flyway, HTTP, health and messaging configuration

---

### Details
- **Priority:** High (blocks everything)
- **Estimate:** 5 story points

---

### Preconditions
- rgctl index present (`.rgctl/`) and migration prompt approved

---

### Steps to Reproduce / Implementation Notes
1. Replace the POM dependency block with Quarkus extensions and BOM-managed versions
2. Add the Quarkus Maven plugin with `build`, `generate-code`, `generate-code-tests` goals
3. Delete Java EE deployment descriptors and vendor shims
4. Add `src/main/resources/application.properties` with env-overridable defaults
5. Validate with `mvn -q -DskipTests package` (requires JDK 21 + Maven; unavailable in the execution harness — see Story 8 notes)

---

### Dependencies
- None (first story)

---

### Test Cases
- Test 1: `mvn -DskipTests package` produces `target/quarkus-app/quarkus-run.jar` — expected: build succeeds
- Test 2: `grep -R "javax.ejb\|javax.jms\|weblogic" src/main/java` — expected: no matches
- Test 3: `mvn quarkus:dev` starts on port 8080 — expected: application boots with no deployment descriptors

---

### Notes / Comments
- System-scoped jar is retained because `com.enterprise:audit-logging-library` is not published to a repository the
  build can reach; adding a `lib/` note in the README is part of the deliverable.
- Quarkus 3 removed the `webapp` directory from the static-resource path — resolved in Story 6.

---

## User Story 2: Persistence layer on Jakarta Persistence

**As a:** Backend Developer
**I want:** The catalog, inventory and order entities to persist through Hibernate ORM 6 with the existing PostgreSQL schema
**So that:** No data migration is required and the Flyway-managed schema stays authoritative

---

### Tasks
- ## Task Group 2: Persistence layer (JPA 2.1 → Jakarta Persistence 3.1) (from tasks.md)

### Acceptance Criteria
- [ ] Entities compile against `jakarta.persistence` and keep identical table/column names (`PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`)
- [ ] `Order`/`OrderItem` inserts use the existing `hibernate_sequence` (no missing-sequence error at runtime)
- [ ] Stray `@Column(name="TOTAL_PRICE")` on the `itemList` association removed; `@XmlRootElement` removed
- [ ] `Resources` producer deleted; `EntityManager` injected directly and mutating methods are `@Transactional`
- [ ] Flyway runs `V1_1__CreateSchema.sql` + `V1_2__AddInitialData.sql` at start-up on a fresh database

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Story 1 complete (Quarkus POM and `application.properties`)

---

### Steps to Reproduce / Implementation Notes
1. Re-package imports to `jakarta.persistence` and annotate the sequence explicitly
2. Replace EJB `@Stateless` with `@ApplicationScoped` and add `@Transactional` where state changes
3. Configure `quarkus.datasource.*`, `quarkus.hibernate-orm.*`, `quarkus.flyway.migrate-at-start=true`,
   `quarkus.flyway.baseline-on-migrate=true`
4. Verify seed data is visible through `/services/products` (Story 5)

---

### Dependencies
- Story 1

---

### Test Cases
- Test 1: fresh database start-up — expected: Flyway applies 2 migrations, `PRODUCT_CATALOG` has 9 rows
- Test 2: persist an `Order` with 2 items — expected: `ORDERS` + `ORDER_ITEMS` rows with generated IDs from `hibernate_sequence`
- Test 3: `getCatalogItemById` for unknown id — expected: `null` (no exception)

---

### Notes / Comments
- Hibernate 6 renamed the default sequence generator; without the explicit `@SequenceGenerator` every order insert fails.
  This is a real defect surfaced by the migration, not a schema change.

---

## User Story 3: Business services and cart state

**As a:** Shopper
**I want:** Cart pricing, promotions, shipping and checkout to behave exactly as they do today
**So that:** Customers see the same totals and discounts after the migration

---

### Tasks
- ## Task Group 3: Domain services (EJB 3.2 → CDI beans) (from tasks.md)

### Acceptance Criteria
- [ ] `ShippingService.calculateShipping` returns 2.99 / 4.99 / 6.99 / 8.99 / 10.99 for the same total bands and 0 above 10,000
- [ ] `calculateShippingInsurance` returns 2% / 1.5% / 1% of cart total for 25–100 / 100–500 / 500–10,000, rounded HALF_UP to 2 dp
- [ ] `PromoService` keeps the 25% discount for item `329299` and free shipping at cart item total ≥ 75
- [ ] Cart operations (add, dedupe by item id, remove partial quantity, transfer between carts, reset on checkout) work per `cartId`
- [ ] No EJB annotations, no `@Remote`, no `ejb:/ROOT/...` JNDI lookup remain

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 2 complete (catalog lookups used by pricing)

---

### Steps to Reproduce / Implementation Notes
1. Collapse `ShippingServiceRemote` into a single `@ApplicationScoped` bean and inject it directly
2. Introduce `CartStore` (`ConcurrentHashMap<String, ShoppingCart>`) and re-implement `ShoppingCartService` against it
3. Keep the pricing algorithm structure (`initShoppingCartForPricing` → item promos → shipping → insurance → free-shipping → total)
4. Replace the CDI `Logger` producer with `org.jboss.logging.Logger`

---

### Dependencies
- Story 2

---

### Test Cases
- Test 1: cart with 1 × `329299` (10.00) — expected: item promo −2.50, shipping 2.99, total 10.48
- Test 2: cart item total 75.00 — expected: shipping 0.00 and `shippingPromoSavings` = −shipping fee
- Test 3: add same item twice — expected: single deduped line with summed quantity
- Test 4: transfer cart A → cart B — expected: B holds A's items, A is empty after re-pricing
- Test 5: checkout — expected: order emitted, cart items cleared, totals reset

---

### Notes / Comments
- **Behaviour change (documented):** cart state was node-local (`@Stateful` per session). It is now a `cartId`-keyed store,
  which makes carts survive across replicas but is process-local. A Redis-backed store is the follow-up for multi-replica
  session affinity; the HTTP contract is unchanged.

---

## User Story 4: Order pipeline on Kafka

**As a:** Operations Analyst
**I want:** Checkout to publish orders that are both persisted and checked against inventory thresholds
**So that:** Orders are recorded and stock alerts keep firing exactly as they did with the JMS topic

---

### Tasks
- ## Task Group 4: Messaging (JMS topic → Kafka, MDB → Reactive Messaging) (from tasks.md)

### Acceptance Criteria
- [ ] Checkout emits one JSON order message to Kafka topic `orders`
- [ ] Both consumers receive every order (two channels / two consumer groups — topic fan-out preserved)
- [ ] `OrderMessageConsumer` persists the order and decrements inventory for each line
- [ ] `InventoryNotificationConsumer` logs a low-stock warning when projected quantity < 50
- [ ] Wire format unchanged: `orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`,
      `shippingDiscount`, `items[{productSku, quantity}]`
- [ ] No `javax.jms`, no `JMSContext`, no `@MessageDriven`, no WebLogic T3 JNDI remain

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 3 complete (checkout path exists)
- Kafka broker available (AMQ Streams in OpenShift, Redpanda/Kafka in dev)

---

### Steps to Reproduce / Implementation Notes
1. Configure `mp.messaging.outgoing.orders-out` and the two `incoming` channels with distinct `group.id`
2. Replace `ShoppingCartOrderProcessor` JMS producer with an `Emitter<String>`
3. Port MDB bodies into `@Incoming` methods with `@Transactional` on the persisting consumer
4. Serialize with Jackson `ObjectMapper` (replaces JSON-P) keeping field names stable

---

### Dependencies
- Story 3
- Kafka infrastructure (Story 10)

---

### Test Cases
- Test 1: checkout a cart — expected: one message on `orders`, both consumers invoked
- Test 2: JSON round-trip `shoppingCartToJson` → `jsonToOrder` — expected: identical values and item list
- Test 3: order for 700 units of a 736-unit item — expected: inventory becomes 36 and a low-stock warning is logged
- Test 4: broker unavailable during checkout — expected: checkout returns the cart without a hard failure (documented)

---

### Notes / Comments
- A single Kafka consumer group would load-balance and silently drop one handler — the two-group configuration is a
  deliberate semantic mapping of JMS topic fan-out. This is the highest-risk behavioural detail in the migration.
- `InventoryNotificationMDB` in the legacy code was never wired (WebLogic T3 lookup against JBoss); it is now a real consumer.

---

## User Story 5: REST API contract preservation

**As a:** Front-end Developer
**I want:** Every `/services` endpoint to keep its path, verb and JSON shape
**So that:** The AngularJS SPA runs unmodified against the Quarkus backend

---

### Tasks
- ## Task Group 5: REST layer (JAX-RS 2.0 → Jakarta REST 3.1 on Quarkus) (from tasks.md)

### Acceptance Criteria
- [ ] `/services/products` and `/services/products/{itemId}` return the same product JSON (`itemId`, `name`, `desc`, `price`, `location`, `quantity`, `link`)
- [ ] Cart endpoints (`GET /{cartId}`, `POST /{cartId}/{itemId}/{quantity}`, `DELETE /{cartId}/{itemId}/{quantity}`,
      `POST /{cartId}/{tmpId}`, `POST /checkout/{cartId}`) behave identically
- [ ] `/services/orders` and `/services/orders/{orderId}` return the same order JSON (`shoppingCartItemList` is cart-only;
      order exposes `itemList`)
- [ ] Unknown product returns the same status/body as before (documented)
- [ ] Resources are Jakarta REST (`jakarta.ws.rs`) singletons with no `Serializable`/`@SessionScoped` leftovers

---

### Details
- **Priority:** High (rank-1 community in the roadmap)
- **Estimate:** 5 story points

---

### Preconditions
- Story 3 (services) complete

---

### Steps to Reproduce / Implementation Notes
1. Re-package `javax.ws.rs` → `jakarta.ws.rs`, keep `@ApplicationPath("/services")`
2. Delegate cart endpoints to the `cartId`-keyed service (no per-session bean)
3. Keep Jackson field names identical; do not introduce DTO renaming
4. Add RestAssured characterization tests before changing behaviour

---

### Dependencies
- Story 3

---

### Test Cases
- Test 1: `GET /services/products` — expected: 9 products with seed data order
- Test 2: `POST /services/cart/c1/329299/1` — expected: cart JSON with `cartItemTotal` 10.0, `cartTotal` 10.48
- Test 3: `DELETE /services/cart/c1/329299/1` — expected: empty cart, totals 0
- Test 4: `POST /services/cart/checkout/c1` — expected: emptied cart, order published and retrievable via `/services/orders`

---

### Notes / Comments
- `ProductEndpoint` returns `null` for an unknown item today (204 No Content). Preserving that quirk is intentional —
  changing it to 404 would be a contract break outside this migration's scope.

---

## User Story 6: Front end and health endpoints served by Quarkus

**As a:** Shopper
**I want:** The storefront to load from the Quarkus application root and health probes to be available
**So that:** The UI works unchanged and OpenShift can manage the pod lifecycle

---

### Tasks
- ## Task Group 6: Static front end and health endpoints (from tasks.md)

### Acceptance Criteria
- [ ] `/` serves `index.html` with all `/bower_components/**`, `/app/**` and `/partials/**` assets resolvable
- [ ] `coolstore.json` (`MONOLITH: true`) and `keycloak.json` are served from the root
- [ ] `/q/health`, `/q/health/live`, `/q/health/ready` return `UP`; `/q/openapi` documents the `/services` API
- [ ] No JSP files remain in the application

---

### Details
- **Priority:** Medium
- **Estimate:** 3 story points

---

### Preconditions
- Story 5 complete (API reachable from the SPA)

---

### Steps to Reproduce / Implementation Notes
1. `git mv src/main/webapp/{app,bower_components,partials,*.json} src/main/resources/META-INF/resources/`
2. Rewrite `index.jsp` as `index.html` without the session scriptlet
3. Add `quarkus-smallrye-health` + `quarkus-smallrye-openapi`, remove `health.jsp`
4. Smoke-test the SPA flow with `mvn quarkus:dev`

---

### Dependencies
- Story 5

---

### Test Cases
- Test 1: `GET /` — expected: 200 `text/html` containing `ng-view`
- Test 2: `GET /coolstore.json` — expected: `{"MONOLITH": true, ...}`
- Test 3: `GET /q/health/ready` — expected: `{"status":"UP"}` with datasource check
- Test 4: browser add-to-cart → checkout — expected: same UX as the EAP deployment

---

### Notes / Comments
- Vendored bower assets are moved verbatim; modernizing the front end (AngularJS EOL) is explicitly out of scope but
  recorded as a follow-up recommendation.

---

## User Story 7: Modern audit logging (Konveyor mandatory rules)

**As a:** Compliance Officer
**I want:** Order activity audited through `audit-logging-library` 2.0.0 with asynchronous, streamed events
**So that:** We satisfy the repository's mandatory Konveyor rules and do not block the request path on audit I/O

---

### Tasks
- ## Task Group 7: audit-logging-library 1.0.0 → 2.0.0 (Konveyor mandatory rules) (from tasks.md)

### Acceptance Criteria
- [ ] Dependency is `audit-logging-library:2.0.0` and the build targets Java 21 (`audit-logging-0001`)
- [ ] `StreamableAuditLogger` replaces `FileSystemAuditLogger`, configured with TCP host/port (`audit-logging-0003`)
- [ ] `AuditEvent` record is constructed directly and published via `logEventAsync` (`audit-logging-0002`, `0004`, `0005`)
- [ ] An audit event is emitted when an order is persisted, with result `SUCCESS` or `FAILURE`
- [ ] Audit failures are swallowed/logged, never propagated to checkout
- [ ] `rgctl rules run rules` reports 0 violations

---

### Details
- **Priority:** High (mandatory rules)
- **Estimate:** 5 story points

---

### Preconditions
- Story 1 (dependency swap) and Story 4 (order persistence path)

---

### Steps to Reproduce / Implementation Notes
1. Inspect the 2.0.0 jar API (`AuditEvent` 14-component record, `StreamableAuditLogger`, `AuditLogger` interface)
2. Add `AuditService` that builds the logger from `audit.stream.*` configuration and closes it on shutdown
3. Build `AuditEvent` with `AuditContext` fields where available; call `logEventAsync`
4. Re-run `rgctl rules run rules` and attach the result to the execution report

---

### Dependencies
- Story 1, Story 4

---

### Test Cases
- Test 1: configuration binding — expected: host/port/protocol honoured from properties
- Test 2: `AuditEvent` construction — expected: all 14 components populated, timestamp not null
- Test 3: unreachable audit collector — expected: checkout still succeeds, warning logged
- Test 4: `rgctl rules run rules rules/` — expected: `violations: []`

---

### Notes / Comments
- The 1.0.0 library was instantiated in `OrderService` but never used to log — the migration both upgrades the API and
  makes the audit trail real. `StreamableAuditLogger` needs Jackson on the classpath (provided by `quarkus-jackson`).

---

## User Story 8: Verified, containerized, deployable migration

**As a:** Release Engineer
**I want:** A tested, containerized Quarkus image with OpenShift manifests, applied in one command
**So that:** The migrated application can be released and rolled back safely

---

### Tasks
- ## Task Group 8: Test suite and behaviour verification (from tasks.md)
- ## Task Group 9: Containerization (from tasks.md)
- ## Task Group 10: OpenShift deployment (from tasks.md)
- ## Task Group 11: Documentation, traceability, and final verification (from tasks.md)

### Acceptance Criteria
- [ ] Unit + `@QuarkusTest` characterization tests cover shipping bands, promotions, cart flows, JSON contract and consumers
- [ ] `Containerfile` builds on UBI 9 + OpenJDK 21, runtime image runs as non-root UID 185 and listens on 8080
- [ ] `deploy/openshift/` contains Deployment, Service, Route, ConfigMap, Secret, PostgreSQL and `KafkaTopic`, applied via `oc apply -k`
- [ ] Readiness/liveness probes point at `/q/health/*`; resources and security context are set
- [ ] `README.md` documents build, run, configuration, API contract, testing and rollout/rollback
- [ ] Static verification: no `javax.*` enterprise imports, no `weblogic`, no EJB/JNDI, no `@MessageDriven`, no `javax.json`

---

### Details
- **Priority:** High
- **Estimate:** 13 story points

---

### Preconditions
- Stories 1–7 complete

---

### Steps to Reproduce / Implementation Notes
1. Write tests before touching behaviour where possible (characterization first)
2. Build the image with `podman build -t coolstore-monolith .`
3. `oc apply -k deploy/openshift/`; wait for `Deployment` rollout; check `/q/health/ready` through the Route
4. Capture the migration report and update the orchestration log

---

### Dependencies
- Stories 1–7
- OpenShift cluster + AMQ Streams operator (external)

---

### Test Cases
- Test 1: `mvn verify` — expected: all unit and integration tests pass
- Test 2: `podman run -p 8080:8080 …` with env config — expected: `/q/health/ready` UP, `/services/products` 200
- Test 3: `oc get pods` — expected: `Running`, 1/1 Ready, probes not restarting
- Test 4: `oc rollout undo` — expected: rollback to the previous revision succeeds

---

### Notes / Comments
- **Verification limitation:** the execution harness has no JDK, Maven, Podman or cluster. Build/test/image/cluster
  verification is delivered as runnable artefacts plus static checks; the report states this explicitly instead of
  claiming a green build.
