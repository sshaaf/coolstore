# User Stories

**Migration:** CoolStore Monolith — Java EE 7 on JBoss EAP 7.4 → Quarkus 3.x (Jakarta EE 10 /
Java 21) on Red Hat OpenShift.
**Plan:** [`tasks.md`](./tasks.md) · **Requirements:** [`../mig-prompt-workspace/migration-prompt.md`](../mig-prompt-workspace/migration-prompt.md)

Stories are ordered by dependency: each story is independently demonstrable, but must not
start before its `Dependencies` are complete. Estimates assume 1–2 engineers and are
derived from the actual code size (1,785 LOC / 26 classes / 5 packages) and from rgctl's
finding of **zero Java package cycles and zero high blast-radius functions**.

---

## User Story 1: Freeze Current Behaviour Before Changing Anything

**As a:** developer on the migration team
**I want:** a captured baseline of the legacy app's responses, business rules, and performance
**So that:** I can prove the migrated version behaves identically and roll back safely

---

### Tasks
- ## Task Group 1: Baseline, Contract Freeze & Test Harness (from tasks.md)

### Acceptance Criteria
- [ ] `ROOT.war` builds and runs locally against PostgreSQL, Artemis, and Keycloak per README
- [ ] Golden JSON responses stored under `src/test/resources/golden/` for all 7 endpoint shapes
- [ ] Characterization tests cover all shipping brackets, both promotion rules, cart pricing totals, and the `shoppingCartToJson`→`jsonToOrder` round-trip
- [ ] `mvn test` runs (i.e. `maven.test.skip=true` is removed) and the suite is green against legacy code
- [ ] `docs/perf-baseline.md` contains p95/p99 and throughput for catalog list, cart get, cart add, checkout

---

### Details
- **Priority:** High (everything else depends on it)
- **Estimate:** 8 story points

---

### Preconditions
- PostgreSQL, Artemis, and Keycloak 20 available locally (or via containers)
- Maven and JDK 21 installed (legacy code compiles at source level 8)

---

### Steps to Reproduce / Implementation Notes
1. Build and deploy the legacy WAR; seed the database via Flyway startup migration.
2. Use REST Assured (or curl + fixtures) to record responses for the seven endpoint shapes, including one promotion case and one checkout case.
3. Port the pure-function rules into tests (shipping tiers 0–25/25–50/50–75/75–100/100–10000; 25% off item `329299`; free shipping ≥ 75; insurance 2%/1.5%/1%).
4. Run a fixed-load benchmark and record the numbers.

---

### Dependencies
- None (start here)

---

### Test Cases
- Test 1: `calculateShipping` for cart totals 10, 30, 60, 80, 150, 20000 → 2.99, 4.99, 6.99, 8.99, 10.99, 0
- Test 2: `calculateShippingInsurance` for 30, 200, 800 → 2% / 1.5% / 1% with HALF_UP rounding to 2 dp
- Test 3: cart with 2× item `329299` (price 10.00) → `promoSavings = -5.00`, line price 7.50
- Test 4: cart total 80 → `shippingTotal = 0`, `shippingPromoSavings` = negative of original shipping
- Test 5: golden JSON for `GET /services/products` matches field names/types exactly

---

### Notes / Comments
- This story is the safety net: without it, the `javax`→`jakarta` flip silently changes behaviour.
- Do not fix bugs found here — record them as known behaviour and preserve it (parity over correctness).

---

## User Story 2: Stand Up a Compiling Quarkus / Java 21 Skeleton

**As a:** developer
**I want:** the project to build and boot as a Quarkus 3 app on Java 21 with no `javax` or `weblogic` code left
**So that:** the rest of the migration has a working foundation

---

### Tasks
- ## Task Group 2: Quarkus Foundation & `javax` → `jakarta` Conversion (from tasks.md)

### Acceptance Criteria
- [ ] `mvn -B package` produces `target/quarkus-app` with Java 21 release level
- [ ] Zero `javax.*` imports remain in `src/main/java`; zero files remain under `src/main/java/weblogic/**`
- [ ] `StartupListener`, the WildFly JNDI lookup, and `@XmlRootElement` are gone
- [ ] `application.properties` contains only env-indirected configuration (`${ENV:default}`), no secrets
- [ ] App boots and `/q/health/live` returns 200
- [ ] A container image builds from the multi-stage `Containerfile`

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 1 complete (tests exist to catch regressions)
- Target Quarkus 3.x LTS version agreed

---

### Steps to Reproduce / Implementation Notes
1. Replace Java EE `provided` dependencies with `quarkus-bom` + the extension set (REST/Jackson, Hibernate ORM, JDBC PostgreSQL, Flyway, SmallRye Health, OIDC, Redis, Artemis JMS, Micrometer).
2. Mechanically convert all `javax.*` → `jakarta.*` (note `javax.json`→`jakarta.json`; `javax.xml.bind` has no Jakarta 10 equivalent — delete its usage).
3. Delete vendor shims (`weblogic/**`, `StartupListener`) and the `InitialContext`-based EJB self-lookup.
4. Move to `jar` packaging; keep `finalName` meaningful and keep `/services` as the REST base path in config.
5. Build the `Containerfile` early so every later story produces a deployable image.

---

### Dependencies
- Story 1

---

### Test Cases
- Test 1: `mvn -B package` succeeds from a clean `~/.m2` (with the audit jar installed)
- Test 2: boot test — `/q/health/live` → 200, `/q/health/ready` → 200 once datasource task lands
- Test 3: `grep -r "javax\." src/main/java` returns nothing; `find src/main/java/weblogic` returns nothing
- Test 4: container image builds, runs as non-root, listens on 8080

---

### Notes / Comments
- The namespace flip is the single biggest mechanical risk: it must be one coordinated commit and must compile before behaviour is evaluated.
- Do not attempt to preserve `InventoryNotificationMDB`'s WebLogic T3 client — it is dead code (no activation annotation).

---

## User Story 3: Keep the Data Model and Seed Data Working on PostgreSQL

**As a:** store operator
**I want:** the catalog, inventory, orders, and order items to persist exactly as before, with schema and seed data applied automatically
**So that:** no data is lost or reshaped during the migration

---

### Tasks
- ## Task Group 3: Persistence & Data Layer (from tasks.md)

### Acceptance Criteria
- [ ] The 4 entities map to the same tables/columns (`PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`, `ORDER_ID`, `TOTAL_PRICE`)
- [ ] Flyway applies `V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql` at startup on an empty database
- [ ] The same migrations baseline safely against a copy of the legacy database (`baseline-on-migrate=true`, `baseline-version=1.1`)
- [ ] 9 products and 9 inventory rows are present after migration
- [ ] `CatalogItemEntity.inventory` one-to-one and `Order → OrderItem` cascade/orphan-removal behave as before
- [ ] `DataBaseMigrationStartup` and `persistence.xml` are deleted

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Story 2 complete (Quarkus build boots)
- A legacy database dump available for the baseline test

---

### Steps to Reproduce / Implementation Notes
1. Move the two SQL migrations to the Quarkus Flyway location and enable `migrate-at-start`.
2. Replace the JTA datasource / `Resources` producer with Quarkus datasource + `EntityManager` injection.
3. Verify both fresh-install and existing-database paths (the legacy code called `baseline()` then `migrate()` explicitly — replicate that ordering through config).
4. Run the parity checks against the legacy entity mappings.

---

### Dependencies
- Story 2

---

### Test Cases
- Test 1: empty DB → app start → tables exist with expected types; seed counts 9/9/0/0
- Test 2: legacy DB dump → app start → Flyway baselines at 1.1 and applies 1.2 idempotently
- Test 3: `getCatalogItemById("329299")` returns inventory location `Raleigh`, quantity 736
- Test 4: persisting an `Order` with 2 items and re-reading it returns both items with quantities

---

### Notes / Comments
- Keep numeric column types (`float8`, `int4`, `int8`) — the SPA and golden payloads depend on their JSON rendering.
- Flyway baseline mismatch is the most likely data incident; test against a restored dump, not a hand-built schema.

---

## User Story 4: Run Business Logic Without an EJB Container

**As a:** developer
**I want:** catalog, product, promotion, shipping, and order logic to run as CDI beans with explicit transactions
**So that:** the app no longer depends on JBoss EJB/JNDI features

---

### Tasks
- ## Task Group 4: Business Services (EJB → CDI) (from tasks.md)

### Acceptance Criteria
- [ ] All 9 EJB components are `@ApplicationScoped`/`@RequestScoped` CDI beans; no `javax.ejb`/`jakarta.ejb` imports
- [ ] `ShoppingCartService` no longer performs a JNDI lookup; `ShippingService` is injected
- [ ] `OrderService.save` and inventory updates run inside `@Transactional` boundaries
- [ ] Promotion (25% off `329299`, free shipping ≥ 75) and shipping tiers are unchanged (Story 1 tests green)
- [ ] `InventoryNotificationMDB` is either deleted or explicitly scheduled for re-implementation, with the decision recorded

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 3 complete (EntityManager available)

---

### Steps to Reproduce / Implementation Notes
1. Convert each `@Stateless`/`@Stateful`/`@Singleton` bean, keeping method signatures so the REST layer and tests stay stable.
2. Remove `@Remote`, `ShippingServiceRemote`'s remote wiring, `InitialContext`, and the `ejb:/ROOT/...` lookup.
3. Add `@Transactional` where a write spans multiple statements (order save + inventory merge).
4. Move pricing logic out of container-scoped state so Story 5 can externalize the cart.

---

### Dependencies
- Story 3

---

### Test Cases
- Test 1: `updateInventoryItems("329299", 5)` → quantity 736 → 731
- Test 2: `priceShoppingCart` on a 2-item cart produces the same totals as the golden baseline
- Test 3: forced failure during order save rolls back the inventory update (transaction test)
- Test 4: no `jakarta.ejb` or `InitialContext` references remain in `src/main/java`

---

### Notes / Comments
- EJB container-managed transactions are the subtlest behavioural difference; test the rollback path explicitly.
- Keep `PromoService`'s promotion set application-scoped and immutable-by-copy (`new HashSet<>(promotionSet)`) as today.

---

## User Story 5: Carts Survive Restarts and Multiple Replicas

**As a:** shopper
**I want:** my cart to persist when the app pod restarts or my request lands on another replica
**So that:** I never lose items and the platform can scale horizontally

---

### Tasks
- ## Task Group 5: Externalize Cart State (Stateless REST Layer) (from tasks.md)

### Acceptance Criteria
- [ ] Cart state is stored outside the JVM, keyed by the existing `cartId` path parameter
- [ ] `CartEndpoint` and `ShoppingCartService` hold no per-user in-memory state (`@Stateful`/`@SessionScoped` removed)
- [ ] Add → price → dedupe → delete → checkout flows produce identical results to baseline
- [ ] Cart readable after pod restart and from a second replica with no sticky sessions
- [ ] TTL/eviction policy defined and documented; expired carts degrade gracefully (empty cart, no 5xx)

---

### Details
- **Priority:** High (blocks HPA and zero-downtime)
- **Estimate:** 8 story points

---

### Preconditions
- Story 4 complete (pricing logic works without container state)
- Redis (or approved alternative) available; fallback decision approved if not

---

### Steps to Reproduce / Implementation Notes
1. Introduce a `CartStore` abstraction over Redis with Jackson serialization and `cart:{cartId}` keys.
2. Convert `ShoppingCartService` to `@ApplicationScoped` and delegate all cart reads/writes to `CartStore`.
3. Convert `CartEndpoint` to request-scoped; drop HTTP session reliance (also removes `index.jsp`'s `getSession(true)`).
4. Preserve the two-cart `set(cartId, tmpId)` copy semantics and `dedupeCartItems` merging.

---

### Dependencies
- Story 4

---

### Test Cases
- Test 1: add 3 items to cart `abc` → restart app → `GET /services/cart/abc` returns the same 3 items
- Test 2: two replicas behind one service → add on replica A, read on replica B → consistent
- Test 3: add same item twice → quantities merged into one line (dedupe)
- Test 4: expired/missing cart → empty cart payload, HTTP 200
- Test 5: Redis unavailable → clear 5xx with actionable log, no stuck state

---

### Notes / Comments
- This is the highest-architectural-risk story: `@Stateful` + servlet session has no Quarkus equivalent.
- If Redis is vetoed by the platform, the fallback (client-side cart) changes the API contract and needs re-approval before implementation.

---

## User Story 6: Keep the REST API Byte-Compatible for the Existing SPA

**As a:** frontend consumer (the bundled AngularJS storefront)
**I want:** `/services/products`, `/services/cart`, and `/services/orders` to behave exactly as before
**So that:** the UI needs no code changes and users see no regression

---

### Tasks
- ## Task Group 6: REST API Parity (from tasks.md)

### Acceptance Criteria
- [ ] Base path remains `/services`; all 7 endpoint shapes keep their paths, verbs, and status codes
- [ ] JSON payloads match the golden files (field names, types, numeric precision)
- [ ] `POST /{cartId}/{itemId}/{quantity}` vs `POST /{cartId}/{tmpId}` disambiguation still resolves correctly
- [ ] Error responses remain 500 on failure (no accidental 400/404 changes)
- [ ] JSON-P is fully replaced by Jackson; no `javax.json`/`jakarta.json` usage remains

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Stories 4 and 5 complete

---

### Steps to Reproduce / Implementation Notes
1. Configure the Quarkus REST base path instead of `@ApplicationPath`.
2. Port the three endpoint classes, method by method, keeping annotations and signatures.
3. Rewrite `Transformers` with Jackson, preserving field names (`orderValue`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items[].productSku`).
4. Add an `ExceptionMapper` that preserves legacy status codes while adding structured logs.
5. Diff live responses against the golden fixtures.

---

### Dependencies
- Stories 4, 5

---

### Test Cases
- Test 1: `GET /services/products` → identical array to golden (9 products)
- Test 2: `GET /services/products/UNKNOWN` → same "not found" behaviour as legacy (null body/404 — match baseline)
- Test 3: `POST /services/cart/c1/329299/2` → dedupe + repricing matches golden totals
- Test 4: `POST /services/cart/checkout/c1` → returns emptied, repriced cart
- Test 5: `GET /services/orders` after checkout → order object present with all monetary fields

---

### Notes / Comments
- The SPA hard-codes `/services/...` in `app/services/cart.js` and `catalog.js`; path drift breaks the UI silently.
- Do not "improve" response codes or add pagination — contract parity only.

---

## User Story 7: Checkout Still Publishes and Processes Orders Asynchronously

**As a:** store operator
**I want:** checkout to publish each order to `topic/orders`, persist it, and deduct inventory
**So that:** order processing and stock accuracy are preserved

---

### Tasks
- ## Task Group 7: Messaging Pipeline (`topic/orders`) (from tasks.md)

### Acceptance Criteria
- [ ] Checkout publishes exactly one JSON message per order to `topic/orders` with unchanged body shape
- [ ] The consumer persists the `Order` and decrements each item's inventory (same order of operations as today)
- [ ] Message is acknowledged only after both save and inventory update succeed; failures go to a DLQ
- [ ] Broker outage causes a clear error at checkout with no partially persisted order
- [ ] Decision recorded for low-inventory alerting (`InventoryNotificationMDB` replaced or dropped)

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Stories 3, 4, 6 complete
- Artemis/AMQ broker available (or Dev Services in tests)

---

### Steps to Reproduce / Implementation Notes
1. Configure the Artemis JMS extension with destination `topic/orders` and credentials from secrets.
2. Port `ShoppingCartOrderProcessor` to a JMS producer (or `@Channel`), keeping the destination and body.
3. Port `OrderServiceMDB.onMessage` to a CDI consumer with explicit ack-after-success semantics.
4. Add retry/DLQ configuration (`orders.dlq`).
5. Re-verify `checkOutShoppingCart` ordering: publish → reset → re-price → return.

---

### Dependencies
- Stories 3, 4
- Story 6 for the checkout HTTP path

---

### Test Cases
- Test 1: checkout of a 2-item cart → 1 message on `topic/orders`; `ORDERS` gains 1 row; inventory decremented per item
- Test 2: malformed payload → message routed to DLQ, app stays healthy
- Test 3: broker down → checkout returns error, no order row created
- Test 4: message body equals `shoppingCartToJson` output from the baseline (field-for-field)

---

### Notes / Comments
- Keep the destination name `topic/orders` so any external consumer stays compatible.
- The legacy consumer used `Auto-acknowledge`; explicit post-success ack is a deliberate safety improvement — call it out in the runbook.

---

## User Story 8: Audit Logging Uses the v2 Library Without Slowing Checkout

**As a:** compliance auditor
**I want:** every order-processing event emitted as a structured v2 `AuditEvent` streamed to the collector
**So that:** audit coverage is maintained and the app no longer writes audit files to local disk

---

### Tasks
- ## Task Group 8: Audit Logging v2 Upgrade (from tasks.md)

### Acceptance Criteria
- [ ] Dependency is `audit-logging-library:2.0.0` resolved from a real Maven repository (no `system` scope)
- [ ] `FileSystemAuditLogger` and `setLogDirectory("./device-inventory-audit-logs")` are gone; `StreamableAuditLogger` streams over TCP using configured host/port
- [ ] Events are built as the 14-component `AuditEvent` record (no `builder()`)
- [ ] Emissions use `logEventAsync`/`logEventAsyncSafe` (non-blocking); `logEvent` is not used
- [ ] `isReady()` gates emission; `close()` shuts the executor down cleanly at shutdown
- [ ] Collector outage does not fail checkout; events are logged/retried per policy
- [ ] Build and runtime are genuinely Java 21 (v2 uses virtual threads)

---

### Details
- **Priority:** High (mandatory rule `audit-logging-0001`…`0005`)
- **Estimate:** 5 story points

---

### Preconditions
- Story 7 complete (events have a call site)
- Audit collector endpoint (or stub) available in the target environment

---

### Steps to Reproduce / Implementation Notes
1. Install `lib/audit-logging-library-2.0.0.jar` into the build repository (`mvn install:install-file`) or publish it; remove the 1.0.0 system-scope declaration.
2. Build `AuditConfiguration` from `audit.stream.*` config (host, port, buffer size, reconnect ms).
3. Construct `StreamableAuditLogger`, hold it in the bean, close on shutdown.
4. Map each order event to `AuditEvent(timestamp, event_type, user_id, session_id, application, component, action, resource, result, message, details, correlation_id, source_ip, user_agent)`.
5. Emit with `logEventAsyncSafe` on the checkout path.

---

### Dependencies
- Story 7
- Java 21 toolchain (Story 2)

---

### Test Cases
- Test 1: stub TCP listener receives one NDJSON event per order with all record components populated
- Test 2: `result` maps correctly for success and failure paths (`AuditResult`)
- Test 3: collector down → checkout still returns 200, warning logged, no unbounded thread growth
- Test 4: shutdown (`close()`) terminates within the configured timeout
- Test 5: `mvn dependency:tree` shows `com.enterprise:audit-logging-library:2.0.0` with no `system` scope

---

### Notes / Comments
- v2 is an API redesign, not a version bump: file→TCP streaming, builder→record, sync→async.
- This library is why the whole migration targets Java 21 — do not attempt to run it on 17.

---

## User Story 9: Serve the Storefront, SSO, and Health Endpoints from Quarkus

**As a:** shopper
**I want:** the storefront UI, login, and health checks to work as before on the new runtime
**So that:** nothing visible changes for users or for the platform's probes

---

### Tasks
- ## Task Group 9: Web Layer, OIDC & Health (from tasks.md)

### Acceptance Criteria
- [ ] `/` serves the existing AngularJS SPA; `/bower_components/...`, `/app/...`, `/partials/...` all resolve
- [ ] `coolstore.json` and Keycloak config are environment-overridable; no `localhost:8081` hardcoded
- [ ] No JSP/servlet-session dependency remains (`index.jsp`, `health.jsp`, `web.xml`, `beans.xml` removed)
- [ ] OIDC login against realm `eap` / client `eap-app` works end-to-end
- [ ] `/q/health/live` and `/q/health/ready` replace `health.jsp`; readiness reflects DB, Redis, broker
- [ ] Request/latency metrics available for the four hot endpoints

---

### Details
- **Priority:** Medium
- **Estimate:** 5 story points

---

### Preconditions
- Stories 5, 6 complete
- Keycloak instance (or container) with `realm-export.json` imported

---

### Steps to Reproduce / Implementation Notes
1. Move static assets to `src/main/resources/META-INF/resources`, converting `index.jsp` → `index.html`.
2. Drop servlet-session creation; cart identity is the path parameter.
3. Configure `quarkus-oidc` and keep the public-client behaviour the SPA expects.
4. Mount `coolstore.json`/`keycloak.json` from a ConfigMap per environment.
5. Add health and Micrometer; verify probes match OpenShift expectations.

---

### Dependencies
- Stories 5, 6

---

### Test Cases
- Test 1: `GET /` → 200, HTML references `/bower_components/...` and those assets return 200
- Test 2: OIDC login → token issued → SPA proceeds to catalogue
- Test 3: `/q/health/ready` → 503 when DB or Redis is down, 200 when all dependencies are up
- Test 4: `GET /services/products` still works with SSO enabled and disabled (`coolstore.json` flag)

---

### Notes / Comments
- Backend authorization is **not** added here — the legacy app only enforced login in the SPA. Record this as a known gap or a separate decision.
- `health.jsp` returning `1` was the legacy probe contract; replace with SmallRye Health and update the manifests.

---

## User Story 10: Prove Parity and Performance with Automated Tests

**As a:** engineering lead
**I want:** unit, integration, E2E, resilience, and performance tests gating the migration
**So that:** I can approve cutover with evidence rather than optimism

---

### Tasks
- ## Task Group 10: Quality Gates (Unit, Integration, E2E, Performance) (from tasks.md)

### Acceptance Criteria
- [ ] ≥80% line coverage on `service`, `utils`, `model`, `rest` (JaCoCo enforced)
- [ ] Contract-parity gate replays golden fixtures and fails on any drift
- [ ] E2E flow green: browse → add → promotions/shipping → checkout → order persisted → inventory decremented → audit event emitted
- [ ] Resilience tests: DB down, broker down, Redis down, audit collector down — all degrade as specified
- [ ] Performance within 10% of the Story 1 baseline at p95/p99
- [ ] Two-replica rolling update causes zero failed requests

---

### Details
- **Priority:** High (gate for Stories 11–12)
- **Estimate:** 8 story points

---

### Preconditions
- Stories 1–9 complete
- Dev Services or Testcontainers for PostgreSQL/Redis/broker

---

### Steps to Reproduce / Implementation Notes
1. Wire surefire (unit) + failsafe (`@QuarkusTest`) + REST Assured contract replay.
2. Assemble the E2E scenario covering the full business workflow.
3. Add negative/resilience tests with dependency containers stopped mid-run.
4. Re-run the baseline load profile against the container and diff results.
5. Add coverage thresholds so regressions block the pipeline.

---

### Dependencies
- Stories 1–9

---

### Test Cases
- Test 1: coverage report ≥ 80% per package
- Test 2: golden diff returns zero mismatches across all 7 endpoints
- Test 3: E2E produces exactly 1 order row, decremented inventory, and 1 audit event
- Test 4: p95 ≤ baseline + 10% on all four hot endpoints
- Test 5: rolling update with traffic → 0 failed requests, carts preserved

---

### Notes / Comments
- Characterization tests from Story 1 are the backbone; everything else is additive coverage.
- Treat any contract diff as a blocker, not a "known difference".

---

## User Story 11: Ship a Hardened Container Image

**As a:** platform engineer
**I want:** a reproducible, non-root, vulnerability-scanned image containing only the app and a JRE
**So that:** it can be deployed and promoted through OpenShift environments

---

### Tasks
- ## Task Group 11: Containerization Hardening (from tasks.md)

### Acceptance Criteria
- [ ] Multi-stage `Containerfile` with `ubi9/openjdk-21` build and `ubi9/openjdk-21-runtime` run stages
- [ ] Runs as non-root, read-only-rootfs compatible, `EXPOSE 8080`, container-aware JVM flags, graceful shutdown
- [ ] No secrets or environment-specific values baked into the image
- [ ] Image tagged with git SHA and semver; SBOM generated; no unresolved Critical/High CVEs
- [ ] Image published to the OpenShift internal registry (or Quay) with a pull secret documented

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Story 10 complete (tests prove the app is correct)
- Registry access and pull-secret provisioning available

---

### Steps to Reproduce / Implementation Notes
1. Finalize the multi-stage build with dependency caching.
2. Externalize all config; scrub credentials from images and docs.
3. Tune JVM flags (`-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`) and `quarkus.shutdown.timeout`.
4. Publish with immutable tags; generate SBOM; run the scanner in the pipeline.

---

### Dependencies
- Story 10

---

### Test Cases
- Test 1: container boots and `/q/health/live` → 200; `/` serves the SPA
- Test 2: runs as non-root (UID assertion) with read-only root filesystem
- Test 3: scanner report has no Critical/High findings
- Test 4: kill -TERM → graceful shutdown with no dropped in-flight checkout

---

### Notes / Comments
- Prefer digest-pinned base images to keep builds reproducible.
- Do not add or modify anything under `.github/workflows/`; CI is Tekton-managed.

---

## User Story 12: Cut Over to OpenShift with a Tested Rollback

**As a:** operations owner
**I want:** the Quarkus app live on OpenShift behind TLS with probes, scaling, and a ≤15-minute rollback to the legacy WAR
**So that:** the migration lands safely and can be reversed if anything goes wrong

---

### Tasks
- ## Task Group 12: OpenShift Deployment, Cutover & Rollback (from tasks.md)

### Acceptance Criteria
- [ ] Manifests: Deployment (≥2 replicas), Service, Route (TLS), ConfigMap, Secret, ServiceAccount, NetworkPolicy, PDB, HPA, resource limits
- [ ] Startup/liveness/readiness probes on `/q/health/{started,live,ready}`
- [ ] Backing services wired: PostgreSQL, Artemis `topic/orders`, Redis, Keycloak realm `eap`
- [ ] Parallel-run validation passes (golden replay + E2E) before the Route switch
- [ ] Cutover completes with zero data loss; 48 hours of monitored stability
- [ ] Rollback procedure executed once in a drill, completing in ≤15 minutes
- [ ] Alerts cover health, latency, error rate, broker queue depth, Redis memory, audit delivery
- [ ] Legacy JBoss deployment decommissioned and the `pom.xml` OpenShift TODO closed

---

### Details
- **Priority:** High (final story)
- **Estimate:** 8 story points

---

### Preconditions
- Stories 10, 11 complete
- Target namespace, secrets, and backing services provisioned
- Change approval and cutover window agreed

---

### Steps to Reproduce / Implementation Notes
1. Author/curate manifests with kustomize or Helm overlays per environment.
2. Deploy in parallel with legacy; replay golden requests and run E2E on a DB snapshot.
3. Switch the Route; monitor error rate, latency, queue depth, audit delivery, inventory deltas.
4. Drill the rollback: repoint the Route to the legacy WAR, verify cart/order availability.
5. Decommission JBoss, archive the legacy WAR tag, remove residual JBoss/WebLogic references.

---

### Dependencies
- Stories 10, 11

---

### Test Cases
- Test 1: cluster smoke — catalog, cart, checkout, audit event within SLA
- Test 2: HPA scales 2 → 4 replicas under load with no lost carts
- Test 3: pod eviction/rolling update under traffic → no failed requests
- Test 4: rollback drill → legacy app serving within 15 minutes
- Test 5: secret rotation (DB password) → pod restart, healthy, no downtime beyond the rollout

---

### Notes / Comments
- Keep the legacy JBoss EAP deployment warm for the agreed soak period (recommend 30 days) before full decommission.
- CI/CD is owned by the platform (Tekton on OpenShift): the migration must not touch `.github/workflows/`.
