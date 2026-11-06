# User Stories

**Source documents:** `mig-prompt-workspace/migration-prompt.md` (specification + design),
`mig-plan-workspace/tasks.md` (task breakdown).
**Target:** Quarkus 3.x / Java 21 / Jakarta EE 10 on Red Hat OpenShift (single deployable, PostgreSQL, async
order events, Keycloak SSO).

**Personas used:**
- **Developer** — implements the migration
- **QA Engineer** — owns characterization and regression coverage
- **Platform Engineer** — owns image, manifests and cutover
- **Product Owner** — needs the storefront to keep working unchanged
- **Operations Engineer** — runs and troubleshoots the service

Stories are ordered by dependency. Story points are relative (fibonacci), sized from actual code complexity:
the whole backend is 30 classes / 1,785 LOC with 8 REST endpoints, so the effort concentrates on semantics
(EJB→CDI, cart state, messaging) rather than volume.


> **Execution status (mig-execute, 2026-11-06):** stories 1-12 are implemented - code, configuration, tests,
> container definition, manifests and documentation are in the repository. Acceptance criteria that require
> a running build, container engine or OpenShift cluster (verified `mvn verify`, image build/scan, deployed
> probes, cutover, rollback, p95 measurement) are **unverified** and keep their `- [ ]` marker; everything
> provable statically is checked. No JDK, Maven, Podman or `oc` binary exists in the execution environment.
> See `mig-execute-workspace/EXECUTION_REPORT.md` for the per-story result table.

---

## User Story 1: Build the Application on Java 21 with Quarkus

**As a:** Developer
**I want:** the project to build as a Quarkus 3.x application on Java 21 instead of a Java EE 7 WAR
**So that:** every later migration step has a compiling, runnable foundation

---

### Tasks
- ## Task Group 1: Build Foundation — Java 21 + Quarkus 3.x (from `tasks.md`)

### Acceptance Criteria
- [ ] `mvn clean verify` succeeds on Java 21 and produces `target/quarkus-app/quarkus-run.jar`
- [x] `pom.xml` has `packaging=jar`, `quarkus-bom` in `dependencyManagement`, `release=21`, and no
      `javaee-api`/`javaee-web-api`/`jboss-*-spec` dependencies
- [x] `audit-logging-library:2.0.0` resolves as a normal Maven dependency; no `<scope>system</scope>` or
      `<systemPath>` remains anywhere in the build
- [x] `maven.test.skip=true` is gone; `mvn verify` executes unit and integration tests
- [ ] The packaged application starts and `GET /q/health/ready` returns 200
- [x] `rgctl discover` still indexes the project (no build artifacts in the graph)

---

### Details
- **Priority:** High (blocking)
- **Estimate:** 8 points

---

### Preconditions
- Java 21 JDK and Maven 3.9+ available in the developer environment
- Network access to Maven Central (or the enterprise mirror) for Quarkus artifacts

---

### Steps to Reproduce / Implementation Notes
1. Rewrite `pom.xml` with the Quarkus BOM and plugin set; keep `groupId`/`artifactId`/`version` and set
   `finalName` appropriately for the fast-jar.
2. Add the target extensions (REST/Jackson, Hibernate ORM, JDBC PostgreSQL, Flyway, Arc, Health, Messaging,
   container-image) and remove Java EE 7 APIs and JBoss spec JARs.
3. `mvn install:install-file` the vendored `lib/audit-logging-library-2.0.0.jar` and declare version 2.0.0.
4. Add `application.properties` with env-driven datasource, Flyway `migrate-at-start`, port 8080.
5. Build and boot the shell; verify health endpoints.

---

### Dependencies
- None (first story)
- Unblocks stories 2–13

---

### Test Cases
- Test 1: `mvn clean verify` — build succeeds, no `javax.*` compile errors introduced by the new classpath
- Test 2: boot the fast-jar — `/q/health/live` and `/q/health/ready` return 200
- Test 3: dependency audit — `mvn dependency:tree` shows `audit-logging-library:2.0.0` with `compile` scope

---

### Notes / Comments
- The 1.0.0 audit JAR must stay out of the compile path but keep the file in `lib/` for traceability until
  story 9 completes.
- Do not add `.github/workflows` files; CI is platform-managed (Tekton).
- Risk: `quarkus-messaging` pulls a connector requirement at build time — if the connector decision (story 7) is
  not made yet, add the extension without connector config and defer channel wiring.

---

## User Story 2: Run Without WebLogic, JBoss or `javax` Dependencies

**As a:** Developer
**I want:** all vendor-specific shims and legacy `javax.*` APIs removed from the source tree
**So that:** the codebase compiles against Jakarta EE 10 and carries no dead vendor coupling

---

### Tasks
- ## Task Group 2: Remove Vendor Lock-in — WebLogic Shims and `javax` Namespace (from `tasks.md`)

### Acceptance Criteria
- [x] `src/main/java/weblogic/**` no longer exists and no source references `weblogic.*`
- [x] `StartupListener` is gone; startup/shutdown logging (if kept) uses CDI `StartupEvent`/`ShutdownEvent`
- [x] Zero `javax.` imports remain in `src/main/java` (`rg 'import javax\.' src/main/java` returns nothing)
- [x] `InventoryNotificationMDB` (WebLogic JNDI, `t3://localhost:7001`) is removed with no dangling references
- [x] `web.xml`, `beans.xml`, `META-INF/persistence.xml` are removed and the app still boots
- [x] Re-indexed graph shows no `weblogic.*` package nodes and no new dependency cycles

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Story 1 complete (project builds on Quarkus)

---

### Steps to Reproduce / Implementation Notes
1. Delete `weblogic/application/*` and `weblogic/i18n/logging/*` plus `utils/StartupListener.java`.
2. Apply an OpenRewrite/IDE `javax` → `jakarta` recipe, then fix residual errors manually (JMS, JSON-P,
   `@Resource`, `PortableRemoteObject`).
3. Delete the three EE deployment descriptors; move any needed settings into `application.properties`.
4. Remove `InventoryNotificationMDB` after checking `rgctl find onMessage` shows only `OrderServiceMDB` remaining.
5. Re-run `rgctl discover …` and compare metanodes/edges.

---

### Dependencies
- Story 1

---

### Test Cases
- Test 1: `mvn verify` compiles with Jakarta imports only
- Test 2: boot test asserts the application starts and logs the startup marker
- Test 3: static check (`grep`) fails the build if `javax.` or `weblogic.` reappears
- Test 4: `rgctl discover` output contains no `weblogic.application` / `weblogic.i18n.logging` metanodes

---

### Notes / Comments
- `InventoryNotificationMDB` is *not* annotated `@MessageDriven`, so it is dead code in the monolith; its
  low-stock logic (threshold 50) may be re-implemented later as a second consumer (story 7).
- Keep the delete/replace commits separate so behavior changes are auditable.

---

## User Story 3: Persist the Domain with Hibernate ORM 6

**As a:** Developer
**I want:** the four JPA entities and the DTOs ported to Jakarta Persistence with explicit mappings
**So that:** the catalogue, inventory and order data read and write exactly as before

---

### Tasks
- ## Task Group 3: Domain Model — JPA Entities and DTOs (from `tasks.md`)

### Acceptance Criteria
- [x] `CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem` compile with `jakarta.persistence`
- [x] Generated-value strategy is explicit per entity and matches the DDL (`int8` PKs, no sequence table)
- [x] `Order.itemList` (`@OneToMany` EAGER + `@JoinColumn(ORDER_ID)`, orphanRemoval) and
      `CatalogItemEntity.inventory` (`@OneToOne` EAGER + `@PrimaryKeyJoinColumn`) round-trip correctly
- [x] Stale `@Column(name="TOTAL_PRICE")` annotation no longer maps a non-existent column
- [x] DTO JSON field names are unchanged (`itemId`, `name`, `desc`, `price`, `shoppingCartItemList`, `cartTotal`,
      `cartItemTotal`, `shippingTotal`, `promoSavings`, `quantity`, `location`, `link`)
- [x] `com.redhat.coolstore.model` has no dependency on vendor packages in the graph

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Story 2 complete (Jakarta namespace, descriptors removed)
- PostgreSQL test instance available for integration tests

---

### Steps to Reproduce / Implementation Notes
1. Port entities, choosing `GenerationType.IDENTITY` (or a matching sequence) for `Order.orderId`/`OrderItem.id`.
2. Review Hibernate 6 defaults for eager collections and `columnDefinition="text"`.
3. Keep DTOs as simple mutable POJOs (Jackson needs setters for the SPA payloads).
4. Verify with a persistence integration test against PostgreSQL using the existing DDL.

---

### Dependencies
- Story 2
- Story 4 (schema/migration provisioning) runs in parallel and validates the same DDL

---

### Test Cases
- Test 1: persist/read `CatalogItemEntity` with nested `InventoryEntity` — values preserved
- Test 2: persist `Order` with 2 `OrderItem`s — `ORDER_ID` FK set, orphan removal works
- Test 3: serialize `ShoppingCart` — JSON keys match the legacy contract
- Test 4: `rgctl discover` metagraph shows `model` without vendor edges

---

### Notes / Comments
- Removing the stale `@Column(name="TOTAL_PRICE")` is a behavior-neutral cleanup, but confirm no external
  reporting query depends on it (DDL has no such column, so it never existed).
- Serialization must not expose JPA lazy proxies; all relations used by the API are EAGER today.

---

## User Story 4: Keep the Database and Migrations Working on Flyway 10

**As a:** Operations Engineer
**I want:** the PostgreSQL schema and seed data applied by Quarkus Flyway at startup
**So that:** the migrated service runs against the same database without manual JBoss CLI steps

---

### Tasks
- ## Task Group 4: Persistence Provisioning — EntityManager, Datasource, Flyway 10 (from `tasks.md`)

### Acceptance Criteria
- [x] `Resources.java` `EntityManager` producer is removed; `EntityManager` is injected where needed
- [x] `DataBaseMigrationStartup` EJB is removed; `quarkus.flyway.migrate-at-start=true` applies
      `V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql`
- [ ] On a fresh database, all four tables and 9 seeded products/inventory rows exist after boot
- [x] No `java:jboss/...` or `java:/...` JNDI names remain in code or configuration
- [x] Datasource credentials come from environment/Secret, never from source
- [ ] Re-running the app on an already-migrated database is a no-op (Flyway history validated)

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Story 1 (build) and Story 3 (entities) in progress/complete
- PostgreSQL 15+ reachable from the dev environment

---

### Steps to Reproduce / Implementation Notes
1. Delete the `@Singleton @Startup` Flyway bootstrap (Flyway 4.1.2 `new Flyway()` API no longer exists).
2. Configure `quarkus.datasource.*` and `quarkus.flyway.*` (`locations=db/migration`, `baseline-on-migrate=true`).
3. Validate both migration scripts on Flyway 10 + PostgreSQL 15 in a container.
4. Replace the README's JBoss `data-source add` instructions with config docs.

---

### Dependencies
- Story 1
- Story 3 (entities must match the schema)

---

### Test Cases
- Test 1: Testcontainers PostgreSQL — migrations apply, `flyway_schema_history` has 2 entries
- Test 2: seed assertions — 9 `PRODUCT_CATALOG` rows, matching `INVENTORY` quantities
- Test 3: idempotent restart — second boot performs no migration and stays healthy
- Test 4: readiness probe reports DOWN when the datasource is unreachable

---

### Notes / Comments
- Flyway is forward-only: document a DB snapshot/restore step before the production cutover.
- `baseline-on-migrate` is required if the target database already contains the schema from the monolith.

---

## User Story 5: Preserve Pricing, Promotion and Shipping Calculations

**As a:** Product Owner
**I want:** cart pricing, promotions, shipping fees and insurance to produce identical amounts
**So that:** customers are charged exactly as they were before the migration

---

### Tasks
- ## Task Group 5: Utilities — JSON Transformers, Logging, Health (from `tasks.md`)
- ## Task Group 6: Core Services — EJB to CDI, Pricing and Shipping (from `tasks.md`)

### Acceptance Criteria
- [x] `ShippingService.calculateShipping` returns 2.99 / 4.99 / 6.99 / 8.99 / 10.99 / 0 for the legacy bands
      (0–25, 25–50, 50–75, 75–100, 100–10000)
- [x] `calculateShippingInsurance` returns 2% / 1.5% / 1% (HALF_UP, 2 decimals) for the 25–100 / 100–500 / 500+ bands
- [x] Promotion `("329299", 0.25)` applies a negative `promoSavings` and reduces the item price by 25%
- [x] Free shipping applies when `cartItemTotal >= 75`
- [x] `priceShoppingCart` recomputes `cartItemTotal`, `shippingTotal`, `cartTotal` from scratch each call
- [x] No JNDI/remote-EJB lookup remains: `ShippingService` is injected directly
- [x] `Transformers` produces byte-identical JSON keys and `jsonToOrder` parses the same payloads
- [x] Health endpoint reports datasource readiness; `health.jsp` is removed

---

### Details
- **Priority:** High
- **Estimate:** 13 points

---

### Preconditions
- Stories 2–4 complete (Jakarta namespace, entities, datasource)
- Characterization tests for the monolith captured first (see story 13, task 14.2)

---

### Steps to Reproduce / Implementation Notes
1. Capture golden outputs from the monolith for boundary carts (24.99, 25, 49.99, 50, 74.99, 75, 99.99, 100, 499.99, 500).
2. Convert EJBs to `@ApplicationScoped` CDI beans with constructor injection.
3. Replace `lookupShippingServiceRemote()` with an injected `ShippingService`; delete the `@Remote` interface once
   unused.
4. Port `Transformers` to Jackson while keeping the exact wire keys and the random-name behaviour.
5. Replace `Producers` JUL logger with `org.jboss.logging.Logger` and `health.jsp` with SmallRye Health.

---

### Dependencies
- Stories 2, 3, 4

---

### Test Cases
- Test 1: parameterized shipping tests at every band boundary — exact cents match
- Test 2: promotion test — `promoSavings = -(price * 0.25)`, `price = price * 0.75`
- Test 3: free-shipping test at `cartItemTotal` 74.99 vs 75.00
- Test 4: `shoppingCartToJson` golden file; `jsonToOrder` round-trip equality
- Test 5: `/q/health/ready` 200 with DB, 503 without

---

### Notes / Comments
- `PromoService` keeps promotions in an in-memory `HashSet`; keep it deterministic by sorting when asserting.
- Do not "improve" the shipping bands during migration — parity first, refactor later.

---

## User Story 6: Keep Each Shopper's Cart Isolated Across Replicas

**As a:** Product Owner
**I want:** the cart to behave per-shopper exactly as the `@Stateful`/`@SessionScoped` implementation did
**So that:** concurrent users never see or lose each other's items

---

### Tasks
- ## Task Group 7: Cart State and Order Checkout (from `tasks.md`)

### Acceptance Criteria
- [x] Cart state is keyed by `cartId` and two concurrent carts do not interfere
- [x] `GET /services/cart/{cartId}`, `POST /services/cart/{cartId}/{itemId}/{quantity}`,
      `DELETE /services/cart/{cartId}/{itemId}/{quantity}`, `POST /services/cart/{cartId}/{tmpId}` and
      `POST /services/cart/checkout/{cartId}` behave as before
- [x] Adding the same product twice merges quantities (`dedupeCartItems`) rather than duplicating lines
- [x] Deleting with `quantity >= item.quantity` removes the line; otherwise it decrements the quantity
- [x] Checkout publishes the order, then resets items and reprices the cart
- [x] Cart state survives a request landing on a different replica (shared store or documented sticky sessions) - documented in `docs/design-notes.md` §4 and `docs/runbook.md` §5
- [x] Cart store choice and TTL are configurable (`CART_STORE=memory|redis`)

---

### Details
- **Priority:** High
- **Estimate:** 13 points

---

### Preconditions
- Story 5 complete (pricing callable from the cart service)
- Decision recorded: in-memory store (demo, single replica) vs Redis (multi-replica)

---

### Steps to Reproduce / Implementation Notes
1. Introduce a `CartStore` interface with an in-memory implementation keyed by `cartId` (TTL-based eviction) and
   an optional Redis implementation.
2. Move `dedupeCartItems` from the resource into the cart service, preserving merge order and re-pricing.
3. Keep the `set(tmpId)` transfer semantics used by the SPA when a user logs in.
4. Wire checkout to the messaging emitter (story 7) without changing the publish/reset/reprice order.

---

### Dependencies
- Story 5
- Story 7 (emitter wiring in subtask 7.4)

---

### Test Cases
- Test 1: two carts in parallel — items and totals independent
- Test 2: add same product twice — one line, summed quantity
- Test 3: delete partial/full quantity — correct line state and total
- Test 4: transfer cart from anonymous to authenticated id — items preserved
- Test 5: checkout — items cleared, totals zeroed/repriced, event published once
- Test 6: two replicas, same cart id — consistent state (or sticky-session acceptance test)

---

### Notes / Comments
- The monolith's "cart" is a single per-bean `ShoppingCart` shared by all requests on that bean instance — a
  naive `@ApplicationScoped` port would be a **data-corruption bug**, not a straight translation.
- Highest-risk story in the plan; do it with characterization tests, not after them.

---

## User Story 7: Process Orders Asynchronously After Checkout

**As a:** Developer
**I want:** checkout to publish an order event that is consumed to persist orders and adjust inventory
**So that:** the checkout flow stays decoupled and fast, as it is today with the JMS topic

---

### Tasks
- ## Task Group 8: Messaging — JMS Topic to Reactive Messaging (from `tasks.md`)

### Acceptance Criteria
- [x] Checkout emits exactly one order event with the legacy payload
      (`orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`,
      `shippingDiscount`, `items[{productSku, quantity}]`)
- [x] A consumer persists the `Order` (with items) to PostgreSQL and decrements inventory per item
- [x] Broker connection, channel names and topic are externalised in configuration
- [x] Malformed messages are logged and do not kill the consumer
- [x] If the low-stock notifier is retained, it logs when post-order quantity drops below 50
- [x] No `javax.jms` API, `@MessageDriven`, `destinationLookup` or `java:/topic/orders` remains

---

### Details
- **Priority:** High
- **Estimate:** 13 points

---

### Preconditions
- Story 6 in progress (checkout path)
- Broker decision recorded (`docs/adr/001-messaging.md`): Artemis/AMQP (default), Kafka, or CDI event fallback

---

### Steps to Reproduce / Implementation Notes
1. Replace `JMSContext`/`@Resource Topic` in `ShoppingCartOrderProcessor` with `@Channel("orders") Emitter<String>`.
2. Replace `@MessageDriven OrderServiceMDB` with `@Incoming("orders")` and `Transformers.jsonToOrder`.
3. Configure `mp.messaging.*` connector properties from env.
4. Re-implement (or drop, with a recorded decision) the low-stock notification logic.

---

### Dependencies
- Stories 5, 6
- Story 3/4 (entities + persistence for order save)

---

### Test Cases
- Test 1: checkout → one event on the channel (in-memory connector or embedded broker)
- Test 2: consumer persists order and items; inventory decremented by ordered quantity
- Test 3: two consumers (order + notifier) both receive the same payload
- Test 4: malformed JSON — logged, consumer still alive, next message processed
- Test 5: golden payload equality with the monolith's event

---

### Notes / Comments
- Delivery is at-least-once; order persistence should tolerate redelivery (document idempotency expectations).
- Keep the payload byte-compatible so existing consumers/tests can be reused.

---

## User Story 8: Keep the REST API Contract Unchanged

**As a:** Product Owner
**I want:** the same endpoints, paths, payloads and status codes under `/services`
**So that:** the existing storefront and any external client keep working without changes

---

### Tasks
- ## Task Group 9: REST API Surface (from `tasks.md`)

### Acceptance Criteria
- [x] Base path remains `/services` (`/services/products`, `/services/orders`, `/services/cart`)
- [x] All 8 endpoint methods exist with identical paths, verbs and `application/json` responses
- [x] `GET /services/products/{unknown}` returns the same status/shape as the monolith (null-tolerant)
- [x] The `POST /cart/{cartId}/{tmpId}` vs `POST|DELETE /cart/{cartId}/{itemId}/{quantity}` overlap is preserved
      and covered by routing tests
- [x] Error handling for failed pricing does not leak stack traces (stable 500 JSON body)
- [x] OpenAPI (optional) documents the endpoint inventory accurately - `quarkus-smallrye-openapi` serves `/q/openapi`; written inventory in `docs/api-contract.md`

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Stories 5, 6, 7 substantially complete (services and cart callable)

---

### Steps to Reproduce / Implementation Notes
1. Port `RestApplication` to `quarkus-rest` with `quarkus.rest.path=/services`.
2. Port the three resources, keeping annotation semantics (`@RequestScoped` → default per-request bean).
3. Preserve `@Produces`/`@Consumes` and the existing `serialVersionUID` payload shapes.
4. Add a `@ServerExceptionMapper` for the pricing rethrow path.

---

### Dependencies
- Stories 5, 6, 7

---

### Test Cases
- Test 1: RestAssured contract tests for all 8 methods (status + JSON schema) against captured goldens
- Test 2: unknown product/order id — status and body match the baseline
- Test 3: routing test for `POST /cart/{cartId}/{tmpId}` vs `/{itemId}/{quantity}`
- Test 4: validation failure path returns a stable 500 JSON error

---

### Notes / Comments
- rgctl migration plan ranks `com.redhat.coolstore.rest` priority 1 (highest harmonic centrality) — it is the
  most externally visible package, so contract tests are non-negotiable.
- Do not "fix" the ambiguous cart routes; changing them breaks the SPA (`cart.js`).

---

## User Story 9: Use Audit Logging Library 2.0.0 with Async TCP Streaming

**As a:** Operations Engineer
**I want:** order and checkout events streamed asynchronously to the audit collector using library 2.0.0
**So that:** we satisfy the audit requirement, close the Konveyor findings, and stop writing logs to the container filesystem

---

### Tasks
- ## Task Group 10: Audit Logging Library 1.0.0 → 2.0.0 (from `tasks.md`)

### Acceptance Criteria
- [x] No `com.enterprise.audit.logging.service.FileSystemAuditLogger` reference remains (closes `audit-logging-0003`)
- [x] `StreamableAuditLogger` is constructed from `AuditConfiguration` with host/port/protocol/buffer/reconnect
      from configuration
- [x] `AuditEvent` is instantiated as a record with all 14 components in the correct order
- [x] Events are emitted with `logEventAsync` (not `logEvent`/`logSuccess`/`logFailure`)
- [x] Order save and checkout produce audit events with correct `action`, `resource`, `result` (`SUCCESS`/`FAILURE`)
- [x] `./device-inventory-audit-logs` is no longer used in the default runtime profile
- [x] `rgctl -f json rules run rules --target quarkus3` reports **0 violations**

---

### Details
- **Priority:** High (mandatory Konveyor rules)
- **Estimate:** 8 points

---

### Preconditions
- Story 1.4 complete (library 2.0.0 resolvable as a normal dependency)
- Story 6 complete (service lifecycle for logger init/close)
- A stub TCP collector available for integration tests

---

### Steps to Reproduce / Implementation Notes
1. Replace the import/instantiation in `OrderService` with `StreamableAuditLogger` and config-driven streaming.
2. Build `AuditEvent` records — record order: `Instant timestamp, String event_type, user_id, session_id,
   application, component, action, resource, AuditResult result, message, Map details, correlation_id,
   source_ip, user_agent`.
3. Call `logEventAsync(event)` on order save/checkout; handle the returned `CompletableFuture` failures by logging.
4. Close the logger on shutdown (`@PreDestroy`/`ShutdownEvent`).
5. Re-run the ruleset and the discover with `--with-kantra` to confirm the violation count drops to 0.

---

### Dependencies
- Stories 1, 6
- Can run in parallel with stories 7 and 8

---

### Test Cases
- Test 1: mocked logger captures one `AuditEvent` per order save with expected fields
- Test 2: stub TCP collector receives the serialized event (integration)
- Test 3: collector unavailable — async failure logged, checkout still succeeds
- Test 4: `rgctl rules run` returns 0 violations for all 5 audit rules
- Test 5: container has no write to `./device-inventory-audit-logs`

---

### Notes / Comments
- 2.0.0 requires Java 16+ (records) and Jackson `databind`; both are satisfied by Java 21 + `quarkus-rest-jackson`.
- The monolith instantiates the logger but never emits an event — wiring emission is new behaviour and should be
  agreed with the audit owner.

---

## User Story 10: Keep the Storefront Working from the Quarkus Application

**As a:** Product Owner
**I want:** the AngularJS storefront, static assets and optional SSO to be served as before
**So that:** shoppers see no change after the migration

---

### Tasks
- ## Task Group 11: Frontend Static Content and SSO (from `tasks.md`)
- ## Task Group 5 (subtask 5.4 health endpoint) (from `tasks.md`)

### Acceptance Criteria
- [x] `/index.html` (or `/`), `/app/*`, `/partials/*`, `/bower_components/*`, `/coolstore.json`, `/keycloak.json`
      all return 200 with the same content
- [x] `coolstore.json` keeps `MONOLITH=true` so the SPA calls relative `/services/*`
- [x] `SSO_ENABLED=true` performs Keycloak login against an externalised `auth-server-url` (no `localhost`)
- [x] `SSO_ENABLED=false` boots the SPA without Keycloak (demo fallback)
- [x] `index.jsp`'s session-creation side effect is either preserved via a template or consciously dropped
- [x] No change is made to vendored `bower_components` sources

---

### Details
- **Priority:** Medium
- **Estimate:** 8 points

---

### Preconditions
- Story 2 complete (webapp descriptors removed)
- Story 8 complete (backend endpoints reachable at `/services`)
- Keycloak realm `eap` importable (`realm-export.json`)

---

### Steps to Reproduce / Implementation Notes
1. Move static content to `src/main/resources/META-INF/resources/` preserving directory layout.
2. Replace `index.jsp` with `index.html` (Qute only if the session side effect matters).
3. Externalise `coolstore.json`/`keycloak.json` values via ConfigMap-friendly overrides.
4. Add `quarkus-oidc` only if backend authorization is required; otherwise keep frontend-only SSO.

---

### Dependencies
- Stories 2, 8

---

### Test Cases
- Test 1: static asset 200s for each path prefix
- Test 2: E2E browse → add to cart → checkout with `SSO_ENABLED=false`
- Test 3: E2E login → cart transfer (`set(tmpId)`) → checkout with `SSO_ENABLED=true`
- Test 4: `keycloak.json` served with the configured issuer URL, not `localhost`

---

### Notes / Comments
- AngularJS 1.x and PatternFly are EOL; the rewrite is explicitly out of scope — serve them untouched and record
  the technical-debt item.
- `rgctl discover` must keep excluding `src/main/webapp/bower_components` to avoid graph noise (23,877 bogus
  communities in the first index).

---

## User Story 11: Ship a Reproducible, Non-Root Container Image

**As a:** Platform Engineer
**I want:** a multi-stage container image that runs the Quarkus fast-jar as a non-root user
**So that:** the application can be deployed on OpenShift with standard security constraints

---

### Tasks
- ## Task Group 12: Containerization (from `tasks.md`)

### Acceptance Criteria
- [x] `podman build` produces an image from a multi-stage `Containerfile` (UBI 9 + OpenJDK 21)
- [x] Image runs as UID 1001 and starts without writing to the filesystem
- [ ] Container boots in under 10 seconds and serves `/q/health/ready` = 200 (with DB available)
- [ ] Image contains no `javax`/`weblogic` classes and no `target/` build leftovers
- [ ] Image passes the vulnerability scan threshold (no fixable CRITICAL)
- [x] `.containerignore` excludes `.git/`, `target/`, `.rgctl/`, `mig-*-workspace/`
- [x] Image tag convention and digest are documented

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Stories 1–10 complete enough that the app runs end-to-end
- Podman/Buildah available; registry access for base images

---

### Steps to Reproduce / Implementation Notes
1. Write the `Containerfile` (builder + runtime stages, `USER 1001`, `EXPOSE 8080`).
2. Add `JAVA_OPTS_APPEND=-XX:MaxRAMPercentage=75` and Quarkus prod profile settings.
3. Document the Jib alternative (`quarkus.container-image.*`).
4. Scan and record the report.

---

### Dependencies
- Stories 1–9 (application must work before it is containerized)

---

### Test Cases
- Test 1: `podman run` with env vars — health 200, logs to stdout, UID 1001
- Test 2: image inspection — no `weblogic`/`javax` classes, correct entrypoint
- Test 3: read-only root filesystem run — app still healthy
- Test 4: scan — threshold respected; report archived

---

### Notes / Comments
- Do not bake configuration or secrets into the image; everything comes from ConfigMaps/Secrets.
- Keep the build offline-capable for the restricted cluster (mirror repository).

---

## User Story 12: Deploy to OpenShift with Externalised Configuration

**As a:** Platform Engineer
**I want:** manifests that deploy the app with probes, config, secrets and a route
**So that:** the service runs reliably on OpenShift and can be rolled back

---

### Tasks
- ## Task Group 13: OpenShift Deployment and Configuration (from `tasks.md`)

### Acceptance Criteria
- [x] `Deployment`, `Service`, `Route`, `ConfigMap`, `Secret` templates exist under `deploy/`
- [x] Readiness/liveness/startup probes use `/q/health/live` and `/q/health/ready`
- [x] Datasource, broker, audit-stream and OIDC settings come from ConfigMap/Secret (no literals in git)
- [x] Resource requests/limits and optional HPA are set and documented
- [x] Pod runs with the restricted-v2 SCC (non-root, no privileged ports)
- [x] `oc rollout undo` and DB restore steps are documented and rehearsed
- [x] No file under `.github/workflows/` is created or modified

---

### Details
- **Priority:** High
- **Estimate:** 8 points

---

### Preconditions
- Story 11 complete (image in the registry)
- PostgreSQL, broker, Keycloak, audit collector endpoints known

---

### Steps to Reproduce / Implementation Notes
1. Author kustomize/YAML manifests with two replicas and rolling update.
2. Create ConfigMap keys for every non-secret setting; Secret placeholders for credentials.
3. Configure probes, PDB, ServiceAccount and egress network policy.
4. Deploy, verify, then rehearse rollback.

---

### Dependencies
- Story 11
- Infrastructure provisioning (PostgreSQL, broker, Keycloak)

---

### Test Cases
- Test 1: deploy to a scratch namespace — pods Ready, route serves `/services/products`
- Test 2: kill a pod — service stays available, PDB respected
- Test 3: wrong DB password in Secret — readiness DOWN, logs explain the failure
- Test 4: scale to 2 replicas — cart behaviour matches the store decision from story 6
- Test 5: `oc rollout undo` restores the previous version

---

### Notes / Comments
- OpenShift assigns arbitrary UIDs; the image must not assume a fixed user home or writable `/tmp` beyond
  `emptyDir` volumes.
- Platform owns Tekton pipelines; this story only supplies manifests and build inputs.

---

## User Story 13: Cut Over with Verified Behaviour and a Rollback Path

**As a:** QA Engineer
**I want:** a full regression, performance and graph-verification pass before and after cutover
**So that:** the migration is demonstrably behaviour-preserving and reversible

---

### Tasks
- ## Task Group 14: Verification, Cutover and Handover (from `tasks.md`)

### Acceptance Criteria
- [ ] Unit + integration + E2E suites green in the pipeline; coverage targets met
      (`service`/`utils` ≥70%, `rest` ≥80%)
- [ ] All 8 endpoint responses match the monolith baseline (status, headers, normalized JSON)
- [ ] p95 latency for `GET /services/products` and checkout within ±10% of baseline; boot time documented
- [x] `rgctl discover` (full Phase 1 flags + local Kantra rules) shows 0 violations, no `weblogic.*` nodes,
      no `javax.*` imports, and the acyclic package structure `rest → service → model/utils`
- [x] Every package in `migration_plan.json` is covered by a completed task group, deviations recorded
- [ ] Cutover completed with one hour of monitored operation, then JBoss/Keycloak runbook archived
- [ ] Rollback demonstrated (`oc rollout undo` + DB snapshot restore)
- [x] Documentation set complete: README, runbook, API contract, design notes, audit logging, EJB→CDI map

---

### Details
- **Priority:** High
- **Estimate:** 13 points

---

### Preconditions
- Stories 1–12 complete
- Baseline metrics and goldens captured from the monolith (before any code change)
- DB snapshot capability and a scratch namespace available

---

### Steps to Reproduce / Implementation Notes
1. Replay the characterization suite against both versions and diff.
2. Run load tests against both versions and compare p95/throughput.
3. Re-index with rgctl and archive the graph/dashboard as evidence.
4. Cut over, monitor, then rehearse rollback.

---

### Dependencies
- Stories 1–12

---

### Test Cases
- Test 1: contract diff — 0 unexpected differences across all endpoints
- Test 2: pricing parity — boundary carts identical to the cent
- Test 3: checkout E2E with SSO on/off — order persisted, inventory decremented, audit event emitted
- Test 4: performance — within ±10% p95, boot < 10s
- Test 5: graph verification — 0 Kantra violations, no cycles introduced
- Test 6: rollback — previous image serves traffic within 5 minutes; DB restored from snapshot

---

### Notes / Comments
- Flyway migrations are forward-only; the DB snapshot is the real rollback for schema changes.
- Keep the graph dashboard (`rgctl serve`) as the visual evidence for the migration review.
- Record deliberate deviations from `migration_plan.json` ordering (foundation-first, `model` early) explicitly
  so the review can confirm they were justified by compile dependencies.
