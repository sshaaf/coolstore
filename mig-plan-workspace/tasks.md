# Migration Tasks

**Source of truth:** [`mig-prompt-workspace/migration-prompt.md`](../mig-prompt-workspace/migration-prompt.md)
**Current state:** Java EE 7 WAR (`ROOT.war`) on JBoss EAP 7.4, Java 8 source level, 1,785 LOC
across 26 classes in `com.redhat.coolstore.{model,persistence,service,rest,utils}`.
**Target state:** Quarkus 3.x (Jakarta EE 10) on Java 21, single container on OpenShift.
**Ordering rule (dependency-driven):** baseline → build/namespace flip → data → services →
cart state → REST → messaging → audit → web/OIDC → quality gates → containerize → deploy.
This matches the rgctl package communities (`model`, `utils`, `service`, `rest`,
`persistence`) lifted to execution order, with the mandatory `javax`→`jakarta` flip placed
early because it is all-or-nothing per compilation unit.

> Global constraints: never create or modify files under `.github/workflows/`.
> Do not modernize `src/main/webapp/bower_components` or the AngularJS app; it must keep
> working unchanged. Do not commit `.rgctl/` artifacts.

---

## Task Group 1: Baseline, Contract Freeze & Test Harness

- [ ] 1.1 Record a **behavioural baseline** of the legacy app: build `ROOT.war`
  (`mvn package`), deploy to JBoss EAP 7.4 with PostgreSQL + Artemis + Keycloak per
  `README.md`, and capture golden HTTP responses for `/services/products`,
  `/services/products/{itemId}`, `/services/cart/{cartId}`,
  `POST|DELETE /services/cart/{cartId}/{itemId}/{quantity}`,
  `POST /services/cart/checkout/{cartId}`, `/services/orders`, `/services/orders/{orderId}`
  (store JSON under `src/test/resources/golden/`).
- [ ] 1.2 Add a test-capable build: JUnit 5 + AssertJ + REST Assured on the classpath,
  remove `<maven.test.skip>true</maven.test.skip>` from `pom.xml`, bump
  `maven-compiler-plugin` (3.0 → 3.13.0) and set `maven-surefire-plugin`/`failsafe`.
- [ ] 1.3 Write **characterization tests for pure business logic** (framework-free, run
  against today's classes): `ShippingService.calculateShipping` and
  `calculateShippingInsurance` for the 0–25 / 25–50 / 50–75 / 75–100 / 100–10000 / ≥10000
  brackets and the 2% / 1.5% / 1% insurance tiers; `PromoService.applyCartItemPromotions`
  for `329299` (25% off) and `applyShippingPromotions` (free shipping at ≥ 75).
- [ ] 1.4 Write characterization tests for `Transformers.shoppingCartToJson` →
  `jsonToOrder` round-trip (field names `orderValue`, `customerName`, `customerEmail`,
  `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items[].productSku`,
  `items[].quantity`) and for `ShoppingCartService.priceShoppingCart` totals.
- [ ] 1.5 Record the **performance baseline**: p95/p99 latency and throughput for catalog
  list, cart get, cart add, checkout under a fixed load profile; commit the report to
  `docs/perf-baseline.md` (numbers only, no `.rgctl` artifacts).
- [ ] 1.6 **Test Generation**: Use mig-test-gen to formalize the characterization suite
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.service` and
    `com.redhat.coolstore.utils`
  - Expected: runnable unit tests that fail loudly if pricing/shipping/JSON behaviour
    drifts; these become the regression gate for every later task group
- [ ] 1.7 **Containerization**: containerize the *legacy* WAR as a reference build
  - Command: `/skill mig-containerize` for `ROOT.war` on `jboss-eap-7.4` (reference image
    only, not the delivery artifact)
  - Config needs: `DB_*` env vars, JNDI datasource, topic `topic/orders`; used solely to
    reproduce the baseline reproducibly
- [ ] 1.8 **Documentation**: document the baseline procedure and env matrix in
  `docs/baseline.md`
  - Include exact PostgreSQL/Artemis/Keycloak versions and the golden-response inventory

## Task Group 2: Quarkus Foundation & `javax` → `jakarta` Conversion

- [ ] 2.1 Convert `pom.xml` to a Quarkus project: add `quarkus-bom` (3.x LTS),
  `quarkus-maven-plugin`, set `maven.compiler.release=21`, packaging `jar`,
  `finalName=coolstore`, remove `javaee-api`/`javaee-web-api`/`jboss-jms-api_2.0_spec`/
  `jboss-rmi-api_1.0_spec` `provided` dependencies.
- [ ] 2.2 Add the extension set: `quarkus-rest`, `quarkus-rest-jackson`,
  `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-agroal`,
  `quarkus-flyway` (with `quarkus-flyway-postgresql`), `quarkus-smallrye-health`,
  `quarkus-oidc`, `quarkus-redis-client`, `quarkus-artemis-jms`, `quarkus-micrometer`,
  `quarkus-junit5`, `rest-assured`, `quarkus-test-oidc-server`.
- [ ] 2.3 Perform the mechanical namespace flip across all 26 classes: `javax.persistence.*`,
  `javax.ws.rs.*`, `javax.inject.*`, `javax.enterprise.*`, `javax.annotation.*`,
  `javax.ejb.*`, `javax.jms.*`, `javax.json.*`, `javax.xml.bind.*` → `jakarta.*`
  (note: `javax.json` → `jakarta.json`; `javax.xml.bind` → **removed** — see 2.6).
- [ ] 2.4 Delete vendor couplings: remove `src/main/java/weblogic/**` (3 stub classes),
  `com/redhat/coolstore/utils/StartupListener.java`, and the
  `org.wildfly.naming.client` JNDI lookup in
  `ShoppingCartService.lookupShippingServiceRemote()` (replace with an injected
  `ShippingService` in 4.4).
- [ ] 2.5 Create `src/main/resources/application.properties` with the baseline config
  skeleton (`quarkus.http.port=8080`, `quarkus.rest.path=/services`,
  `quarkus.datasource.*`, `quarkus.flyway.*`, `quarkus.oidc.*`, broker, redis, audit
  stream) using `${ENV_VAR:default}` indirection only.
- [ ] 2.6 Remove JAXB usage: drop `@XmlRootElement` from `InventoryEntity`
  (`src/main/java/com/redhat/coolstore/model/InventoryEntity.java`) — JSON is the only
  media type.
- [ ] 2.7 Get `mvn -B package` green with an empty Quarkus app (no EJB/JMS code paths yet
  needed to compile); record the fast-jar output layout (`target/quarkus-app`).
- [ ] 2.8 **Test Generation**: Use mig-test-gen for the build/namespace layer
  - Command: `/skill mig-test-gen` for a smoke `@QuarkusTest` that boots the app
  - Expected: boot test asserting HTTP 200 on `/q/health/live` and no `javax.*` residue
- [ ] 2.9 **Containerization**: add the multi-stage `Containerfile` skeleton now so every
  later group is continuously buildable
  - Command: `/skill mig-containerize` for the Quarkus fast-jar
  - Config needs: `ubi9/openjdk-21` build stage, `ubi9/openjdk-21-runtime` run stage,
    `EXPOSE 8080`, non-root UID, `-XX:MaxRAMPercentage=75`
- [ ] 2.10 **Deployment**: validate the image deploys to a scratch OpenShift namespace
  - Command: `/skill mig-deploy` for `coolstore` (smoke only)
  - Dependencies: none yet; Network: route → port 8080, probes `/q/health/live`
- [ ] 2.11 **Documentation**: rewrite `README.md` build section for Quarkus + Java 21 and
  note the removed WebLogic/JBoss prerequisites

## Task Group 3: Persistence & Data Layer

- [ ] 3.1 Migrate the 4 JPA entities to Jakarta Persistence and Quarkus Hibernate ORM:
  `CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem`
  (`src/main/java/com/redhat/coolstore/model/`). Keep table/column names exactly
  (`PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`, `ORDER_ID`, `TOTAL_PRICE`,
  `description` with `columnDefinition = "text"`).
- [ ] 3.2 Replace `persistence.xml` (JTA datasource `java:jboss/datasources/CoolstoreDS`)
  with Quarkus datasource config; keep `hibernate.jdbc.use_get_generated_keys=false`
  semantics in mind for `@GeneratedValue` on `Order.orderId`/`OrderItem.id`.
- [ ] 3.3 Replace `Resources` (`@PersistenceContext` + `@Produces EntityManager`) with
  Quarkus-supported `EntityManager` injection; delete the producer if redundant.
- [ ] 3.4 Move `src/main/resources/db/migration/V1_1__CreateSchema.sql` and
  `V1_2__AddInitialData.sql` to `src/main/resources/db/migration/` (Quarkus Flyway
  location), configure `quarkus.flyway.migrate-at-start=true`,
  `baseline-on-migrate=true`, `baseline-version=1.1`.
- [ ] 3.5 Delete `DataBaseMigrationStartup` (legacy `@Singleton @Startup` + Flyway 4.1.2
  programmatic API `new Flyway()/baseline()/migrate()`).
- [ ] 3.6 Verify schema/seed parity: 9 products, 9 inventory rows, identical column types,
  and that Flyway applies cleanly to (a) an empty database and (b) a copy of the legacy
  database.
- [ ] 3.7 **Test Generation**: Use mig-test-gen for the data layer
  - Command: `/skill mig-test-gen` for `model` + Flyway
  - Expected: `@QuarkusTest` with Dev Services PostgreSQL asserting schema creation, seed
    counts, `CatalogItemEntity.inventory` one-to-one mapping, and `Order` → `OrderItem`
    orphan-removal cascade
- [ ] 3.8 **Containerization**: container build must now include a database dependency
  - Command: `/skill mig-containerize` for `coolstore` with PostgreSQL
  - Config needs: `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`,
    `QUARKUS_DATASOURCE_PASSWORD` (secret)
- [ ] 3.9 **Deployment**: deploy a scratch PostgreSQL instance and run migrations
  - Command: `/skill mig-deploy` for `postgresql` (dev overlay)
  - Network: allow 5432 from the app pod only
- [ ] 3.10 **Documentation**: document schema ownership (Flyway is authoritative), seed
  data provenance, and how to reset a dev database

## Task Group 4: Business Services (EJB → CDI)

- [ ] 4.1 Convert `CatalogService` (`@Stateless`) to `@ApplicationScoped`; keep
  `getCatalogItems`, `getCatalogItemById`, `updateInventoryItems` signatures and the
  Criteria API queries (or rewrite to HQL/`find`, preserving semantics).
- [ ] 4.2 Convert `ProductService` and `PromoService` to CDI beans; keep
  `@ApplicationScoped` promotion set and the `329299`/25% + free-shipping-≥75 rules
  identical (verified by task 1.3 tests).
- [ ] 4.3 Convert `ShippingService` (drop `@Stateless`/`@Remote`) and delete the remote
  interface wiring; keep the `BigDecimal` `HALF_UP` rounding in `getPercentOfTotal`.
- [ ] 4.4 Inject `ShippingService` directly into `ShoppingCartService`; remove
  `lookupShippingServiceRemote()`, `InitialContext`, `Hashtable` JNDI properties and
  `ejb:/ROOT/ShippingService!...`.
- [ ] 4.5 Convert `OrderService` to `@ApplicationScoped` and add `@Transactional` to
  `save(Order)`; keep the Criteria-based `getOrders()` and `getOrderById(long)`.
- [ ] 4.6 Extract the pricing/promotion/shipping calculation from `ShoppingCartService` if
  needed so it can run without container state (prerequisite for Group 5).
- [ ] 4.7 Delete `InventoryNotificationMDB` (WebLogic T3 client, never activated) or, if
  the business still needs low-inventory alerting, re-implement it as a real Artemis
  consumer in Group 7 (decision recorded in `docs/decisions.md`).
- [ ] 4.8 **Test Generation**: Use mig-test-gen for the service layer
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.service`
  - Expected: unit tests for pricing/promotion/shipping plus `@QuarkusTest` integration
    tests for `CatalogService.updateInventoryItems` (deduction + merge) and
    `OrderService.save/getOrders/getOrderById`
- [ ] 4.9 **Containerization**: no new image requirement — assert the container still
  builds and boots after EJB removal
  - Command: `/skill mig-containerize` for `coolstore` (incremental)
  - Config needs: unchanged from 3.8
- [ ] 4.10 **Deployment**: deploy to the dev namespace against the seeded database and
  smoke-test read paths
  - Command: `/skill mig-deploy` for `coolstore` (dev)
  - Dependencies: postgresql; Network: app → postgres:5432
- [ ] 4.11 **Documentation**: document the EJB→CDI mapping table, transaction boundaries
  (which methods are `@Transactional` and why), and the lifecycle of the promotion set

## Task Group 5: Externalize Cart State (Stateless REST Layer)

- [ ] 5.1 Choose and record the cart storage strategy in `docs/decisions.md`:
  **Redis-backed cart store keyed by `cartId`** (default) vs client-side cart payload.
  Note the API-contract impact of the fallback.
- [ ] 5.2 Implement `CartStore` (Redis via `quarkus-redis-client`): `get(cartId)`,
  `save(cartId, ShoppingCart)`, `reset(cartId)`, JSON serialization with Jackson,
  TTL/configurable eviction, and key namespacing
  (`cart:{cartId}`).
- [ ] 5.3 Convert `ShoppingCartService` from `@Stateful` (per-instance `ShoppingCart cart`
  field) to `@ApplicationScoped` + `CartStore`; preserve
  `getShoppingCart`, `checkOutShoppingCart`, `priceShoppingCart`, `getProduct`,
  `initShoppingCartForPricing` behaviour exactly.
- [ ] 5.4 Convert `CartEndpoint` from `@SessionScoped` to `@RequestScoped`/singleton
  resource; remove reliance on HTTP session identity (cart identity comes from the
  `{cartId}` path parameter only).
- [ ] 5.5 Ensure `dedupeCartItems` and the multi-cart `set(cartId, tmpId)` copy semantics
  still work with externalized state (copy list, re-price, dedupe).
- [ ] 5.6 Add the session-independence acceptance test: start cart → restart the app pod →
  cart still readable; run two replicas behind one Route and verify both serve the same
  cart.
- [ ] 5.7 **Test Generation**: Use mig-test-gen for cart state
  - Command: `/skill mig-test-gen` for `CartStore` + `ShoppingCartService`
  - Expected: tests for TTL, concurrent add/dedupe, restart survival (Dev Services Redis),
    and multi-replica read consistency
- [ ] 5.8 **Containerization**: add Redis to the runtime dependency set
  - Command: `/skill mig-containerize` for `coolstore` with Redis
  - Config needs: `QUARKUS_REDIS_HOSTS` (ConfigMap), optional
    `QUARKUS_REDIS_PASSWORD` (secret)
- [ ] 5.9 **Deployment**: deploy Redis (or bind to a managed instance) in the dev namespace
  - Command: `/skill mig-deploy` for `redis`
  - Network: app → redis:6379; add a `NetworkPolicy`
- [ ] 5.10 **Documentation**: document cart lifecycle, TTL/eviction policy, and the
  scaling implications (HPA now safe; sticky sessions no longer required)

## Task Group 6: REST API Parity

- [ ] 6.1 Configure `quarkus.rest.path=/services` to preserve `@ApplicationPath("/services")`;
  delete `RestApplication` (or convert to a config-equivalent) so no path changes occur.
- [ ] 6.2 Port `ProductEndpoint` (`/products`, `/products/{itemId}`) and `OrderEndpoint`
  (`/orders`, `/orders/{orderId}`) to Quarkus REST; keep `@Consumes`/`@Produces`
  `application/json` and identical status codes (including empty-path `@Path("/")`).
- [ ] 6.3 Port `CartEndpoint` with all five operations and their exact sub-paths:
  `GET /{cartId}`, `POST /checkout/{cartId}`, `POST /{cartId}/{itemId}/{quantity}`,
  `POST /{cartId}/{tmpId}`, `DELETE /{cartId}/{itemId}/{quantity}`; note the intentional
  path-overlap resolution between `POST /{cartId}/{itemId}/{quantity}` and
  `POST /{cartId}/{tmpId}` (segment-count disambiguation).
- [ ] 6.4 Replace JSON-P serialization in `Transformers` with Jackson, keeping JSON field
  names/types identical (golden files from 1.1 must match byte-for-byte on field names and
  numeric precision).
- [ ] 6.5 Add consistent error mapping: today `CartEndpoint` throws raw exceptions
  (`throws Exception` → container 500). Preserve 500-on-failure behaviour, but add a
  `ExceptionMapper` that logs structured errors without changing status codes.
- [ ] 6.6 Verify OPTIONS/CORS behaviour expected by the SPA (same-origin in the monolith,
  so no CORS needed — assert nothing regressed).
- [ ] 6.7 **Test Generation**: Use mig-test-gen for REST contracts
  - Command: `/skill mig-test-gen` for `rest` package
  - Expected: REST Assured tests replaying the golden requests, asserting identical JSON
    shapes, plus negative tests (unknown product → `null`/404 semantics as today)
- [ ] 6.8 **Containerization**: rebuild and verify the API surface inside the image
  - Command: `/skill mig-containerize` for `coolstore` (incremental)
  - Config needs: unchanged
- [ ] 6.9 **Deployment**: expose the Route and run the golden-response diff against the
  deployed pod
  - Command: `/skill mig-deploy` for `coolstore` (dev)
  - Network: route (TLS edge) → 8080
- [ ] 6.10 **Documentation**: publish an API parity matrix (endpoint × verb × status ×
  sample payload) in `docs/api-parity.md`

## Task Group 7: Messaging Pipeline (`topic/orders`)

- [ ] 7.1 Move the audit library to a resolvable artifact: `mvn install:install-file`
  for `lib/audit-logging-library-2.0.0.jar` (documented prerequisite, or publish to the
  internal repository), and delete the `<scope>system</scope>`/`systemPath` declaration
  for 1.0.0 from `pom.xml`.
- [ ] 7.2 Configure `quarkus-artemis-jms`: connection URL, user/password (secrets),
  destination `topic/orders`, reconnect/retry settings; add dev/test defaults via Dev
  Services or an embedded broker.
- [ ] 7.3 Port `ShoppingCartOrderProcessor.process(ShoppingCart)` to the Artemis
  `JMSContext`/`JmsProducer` (or Reactive Messaging `@Channel("orders")`), preserving the
  message body produced by `Transformers.shoppingCartToJson` and the log line
  "Sending order from processor: ".
- [ ] 7.4 Port `OrderServiceMDB.onMessage` to an `@ApplicationScoped` consumer
  (`@Incoming("orders")` or a registered Artemis `MessageListener`) keeping the exact
  sequence: parse JSON → `orderService.save(order)` → for each item
  `catalogService.updateInventoryItems(productId, quantity)`.
- [ ] 7.5 Define transaction/acknowledgement semantics explicitly: message ack only after
  both save and inventory update succeed; add retry/DLQ policy (`orders.dlq`) to avoid
  silent message loss.
- [ ] 7.6 Decide on `InventoryNotificationMDB`: keep deleted (Group 4) or re-implement as
  a second consumer of `topic/orders` with the `LOW_THRESHOLD = 50` behaviour; record the
  decision.
- [ ] 7.7 Ensure `checkOutShoppingCart` still returns the re-priced, emptied cart
  immediately after publish (`resetShoppingCartItemList()` then `priceShoppingCart`).
- [ ] 7.8 **Test Generation**: Use mig-test-gen for the messaging pipeline
  - Command: `/skill mig-test-gen` for `ShoppingCartOrderProcessor` + order consumer
  - Expected: integration test that checks out a cart, asserts one message on
    `topic/orders`, then asserts a persisted `Order` and decremented `INVENTORY.quantity`;
    plus a poison-message/DLQ test
- [ ] 7.9 **Containerization**: add broker configuration to the runtime contract
  - Command: `/skill mig-containerize` for `coolstore` with AMQ/Artemis
  - Config needs: `AMQP_URL`/`ARTEMIS_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD` (secret)
- [ ] 7.10 **Deployment**: deploy the broker and verify end-to-end async processing
  - Command: `/skill mig-deploy` for `amq-broker`/`artemis`
  - Network: app ↔ broker:61616 (and console port only if required); NetworkPolicy scoped
- [ ] 7.11 **Documentation**: document the message contract (JSON schema), topic config,
  ack/retry semantics, DLQ handling, and the checkout→inventory sequence diagram

## Task Group 8: Audit Logging v2 Upgrade

- [ ] 8.1 Switch the dependency to `com.enterprise:audit-logging-library:2.0.0`
  (from Group 7.1) and confirm it also brings Jackson `databind`/`jackson-datatype-jsr310`
  and SLF4J without conflicting with Quarkus' Jackson version (use
  `quarkus-jackson` alignment / exclusions as needed).
- [ ] 8.2 Replace initialization in `OrderService.init()`: drop
  `FileSystemAuditLogger` + `config.setLogDirectory("./device-inventory-audit-logs")` +
  `setAutoCreateDirectory(true)`; construct `StreamableAuditLogger(AuditConfiguration)`
  with `setStreamHost`, `setStreamPort`, `setStreamBufferSize`, `setStreamReconnectMs`
  from SmallRye Config (`audit.stream.*`), and keep `close()` in `@PreDestroy`.
- [ ] 8.3 Adopt the record-based `AuditEvent`: map order events to the 14 components
  (`timestamp`, `event_type`, `user_id`, `session_id`, `application`, `component`,
  `action`, `resource`, `result` (`AuditResult`), `message`, `details`, `correlation_id`,
  `source_ip`, `user_agent`). No `builder()` exists in v2.
- [ ] 8.4 Replace deprecated `logEvent(...)` with `logEventAsync(AuditEvent)` (returns
  `CompletableFuture<Void>`), or `logEventAsyncSafe(...)` for fire-and-forget; guard with
  `isReady()` before first use.
- [ ] 8.5 Emit at least the events the legacy code implied for order processing
  (`OrderService.save` success/failure, checkout), with `result` mapped from
  `AuditResult`; keep log levels/lines that the README's clustering example references.
- [ ] 8.6 Confirm readiness to Java 21: v2 uses `Executors.newVirtualThreadPerTaskExecutor()`
  — assert `maven.compiler.release=21`, container runtime JDK 21, and no Java 8 APIs left.
- [ ] 8.7 **Test Generation**: Use mig-test-gen for audit logging
  - Command: `/skill mig-test-gen` for the audit adapter
  - Expected: test with a stub TCP listener asserting one NDJSON event per order with all
    record components populated, plus a test that `close()` terminates the executor within
    the timeout and that a down collector does not fail the checkout path
- [ ] 8.8 **Containerization**: expose audit stream configuration to the container
  - Command: `/skill mig-containerize` for `coolstore` (audit env)
  - Config needs: `AUDIT_STREAM_HOST`, `AUDIT_STREAM_PORT` (ConfigMap),
    buffer/reconnect tuning; egress rule to the collector
- [ ] 8.9 **Deployment**: point the audit stream at the environment's collector (or the
  stub collector in dev) and verify events arrive
  - Command: `/skill mig-deploy` for `coolstore` + audit collector stub
  - Network: app → collector:TCP port; add egress NetworkPolicy
- [ ] 8.10 **Documentation**: document the v1→v2 audit migration (API diff, config keys,
  event schema) and the operational impact of streaming vs local files

## Task Group 9: Web Layer, OIDC & Health

- [ ] 9.1 Move static SPA assets from `src/main/webapp` to
  `src/main/resources/META-INF/resources` (`index.html` from `index.jsp`, `app/`,
  `partials/`, `bower_components/`, `coolstore.json`, `keycloak.json`) so the app serves
  `/` exactly as before; keep asset URLs (`/bower_components/...`) working.
- [ ] 9.2 Remove the JSP servlet-session dependency: delete `index.jsp`'s
  `request.getSession(true)` and `health.jsp`; provide `index.html` + SmallRye Health.
- [ ] 9.3 Configure `quarkus-oidc` for Keycloak (realm `eap`, client `eap-app`), externalize
  `auth-server-url` (`QUARKUS_OIDC_AUTH_SERVER_URL`), keep public-client behaviour, and
  validate the bearer token on protected paths only (today only the SPA enforces login —
  preserve that boundary or add backend enforcement as an explicit decision).
- [ ] 9.4 Delete `src/main/webapp/WEB-INF/{web.xml,beans.xml}` (no servlet deployment
  descriptor / CDI marker needed) and update `src/main/webapp/WEB-INF` references in docs.
- [ ] 9.5 Set `coolstore.json` (`MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT`,
  `SSO_ENABLED`) to be overridable per environment (ConfigMap-mounted file) and remove the
  hardcoded `localhost:8081` from `keycloak.json`.
- [ ] 9.6 Add Micrometer/OpenTelemetry metrics + structured JSON logging aligned with
  OpenShift observability (request counts/latency for the four hot endpoints).
- [ ] 9.7 **Test Generation**: Use mig-test-gen for web/OIDC
  - Command: `/skill mig-test-gen` for static resources + OIDC
  - Expected: tests that `/` serves the SPA, `/bower_components/...` resolves, static
    content is cache-headed correctly, `/q/health/ready` reflects DB+broker+redis
    readiness, and OIDC login flow works with `quarkus-test-oidc-server`
- [ ] 9.8 **Containerization**: final image content review (SPA assets included, no JSP)
  - Command: `/skill mig-containerize` for `coolstore` (final)
  - Config needs: `QUARKUS_OIDC_AUTH_SERVER_URL`, `QUARKUS_OIDC_CLIENT_ID`,
    `QUARKUS_OIDC_CREDENTIALS_SECRET` (secret)
- [ ] 9.9 **Deployment**: deploy Keycloak (or bind to managed RH-SSO/Keycloak), import
  `realm-export.json`, and verify SSO end-to-end
  - Command: `/skill mig-deploy` for `keycloak`
  - Network: SPA/app → keycloak:8443; TLS required
- [ ] 9.10 **Documentation**: rewrite the local-development section (Quarkus dev mode,
  Dev Services), and document realm/client setup and static-asset layout

## Task Group 10: Quality Gates (Unit, Integration, E2E, Performance)

- [ ] 10.1 Consolidate all generated suites into a single Maven lifecycle:
  unit (surefire) → `@QuarkusTest` integration (failsafe) → REST contract replay.
- [ ] 10.2 Add coverage enforcement (JaCoCo) at **80% line coverage** for `service`,
  `utils`, `model`, `rest`; exclude generated/config classes.
- [ ] 10.3 Add the **contract-parity gate**: automated diff of live responses against the
  `src/test/resources/golden/` files from 1.1, failing on any field/status drift.
- [ ] 10.4 Build the E2E suite: browse catalog → add item → verify promotion + shipping
  totals → checkout → order appears in `/services/orders` → inventory decremented →
  audit event emitted.
- [ ] 10.5 Add resilience tests: DB unavailable at startup (readiness false, no crash
  loop), broker down (checkout returns error, no partial order), Redis down (cart
  unavailable, clear 5xx, no data corruption), audit collector down (checkout unaffected).
- [ ] 10.6 Re-run the performance baseline (1.5) against the container: compare p95/p99,
  assert ≤ baseline +10%; record results in `docs/perf-results.md`.
- [ ] 10.7 Add multi-replica/failover test: 2 pods, cart continuity, rolling update with
  zero failed requests.
- [ ] 10.8 **Test Generation**: Use mig-test-gen to close coverage gaps
  - Command: `/skill mig-test-gen` across `com.redhat.coolstore.**`
  - Expected: gap report + additional tests for uncovered branches; no Critical/High
    mutants left unaddressed
- [ ] 10.9 **Containerization**: security scan gate
  - Command: `/skill mig-containerize` for final image + SBOM
  - Config needs: fail build on Critical/High CVEs; pin base image digests
- [ ] 10.10 **Deployment**: run the full suite against a production-like namespace
  - Command: `/skill mig-deploy` for the full stack (dev/staging overlay)
  - Dependencies: postgresql, artemis, redis, keycloak; Network: full mesh policy check
- [ ] 10.11 **Documentation**: publish the test strategy, coverage report, and known
  limitations/test gaps in `docs/testing.md`

## Task Group 11: Containerization Hardening

- [ ] 11.1 Finalize the multi-stage `Containerfile`: dependency-cached build
  (`mvn -B -DskipTests package`), runtime stage `ubi9/openjdk-21-runtime`, non-root user,
  read-only root filesystem compatible, `EXPOSE 8080`.
- [ ] 11.2 Externalize all runtime config; verify zero secrets baked into the image
  (including removal of `postgresPW`-style examples and `realm-export.json` from the
  runtime artifact if not needed).
- [ ] 11.3 Configure JVM flags for container awareness
  (`-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError`) and
  graceful shutdown (`quarkus.shutdown.timeout`).
- [ ] 11.4 Publish the image to the OpenShift internal registry (or Quay) with
  reproducible tags (git SHA + semver) and an image pull secret for the deployment.
- [ ] 11.5 Generate and review the SBOM; record base-image provenance and rebuild cadence.
- [ ] 11.6 **Test Generation**: Use mig-test-gen for image-level checks
  - Command: `/skill mig-test-gen` for image smoke tests
  - Expected: container smoke test (boots, health green, serves `/` and `/services/products`)
  - also `Containerfile` lint/structure test where the pipeline supports it
- [ ] 11.7 **Containerization**: finalization and scan
  - Command: `/skill mig-containerize` for `coolstore` (release build)
  - Config needs: full env/secret matrix documented in `docs/config-matrix.md`
- [ ] 11.8 **Deployment**: deploy the release image to staging via kustomize/Helm overlay
  - Command: `/skill mig-deploy` for `coolstore` (staging)
  - Dependencies: all backing services; Network: Route TLS, probes, PDB, HPA
- [ ] 11.9 **Documentation**: image build/run instructions, registry/vulnerability policy,
  and base-image upgrade runbook

## Task Group 12: OpenShift Deployment, Cutover & Rollback

- [ ] 12.1 Author/curate manifests: `Deployment` (2+ replicas), `Service`, `Route`
  (TLS edge), `ConfigMap`, `Secret`, `ServiceAccount`, `NetworkPolicy`, `PDB`, `HPA`
  (CPU + custom latency metric), resource requests/limits.
- [ ] 12.2 Wire probes: startup `/q/health/started`, liveness `/q/health/live`,
  readiness `/q/health/ready` (DB + Redis + broker included).
- [ ] 12.3 Configure backing services for the target environment: PostgreSQL
  (managed or operator), AMQ/Artemis topic `topic/orders`, Redis, Keycloak realm `eap`.
- [ ] 12.4 Create the secrets (DB password, broker credentials, OIDC client secret, audit
  collector endpoint if sensitive) and confirm no credentials remain in git or the image.
- [ ] 12.5 Dry-run the cutover: deploy in parallel with legacy, replay golden requests and
  the E2E suite, compare order/inventory outcomes on a database snapshot.
- [ ] 12.6 Execute cutover: switch the Route from the legacy JBoss deployment to the
  Quarkus pod; monitor error rate, latency, audit stream, and inventory consistency for
  48 hours.
- [ ] 12.7 Write and test the **rollback procedure**: restore the previous Flyway baseline
  if needed, repoint the Route to the legacy `ROOT.war`, verify cart/order availability,
  target ≤15 minutes.
- [ ] 12.8 Set up monitoring/alerting: health probes, JVM/HTTP metrics, broker queue depth,
  Redis memory, audit stream delivery, error-rate and latency SLOs.
- [ ] 12.9 Decommission: stop the JBoss EAP deployment, remove `weblogic`/JBoss references,
  archive the legacy WAR tag, and close out the `pom.xml` OpenShift TODO.
- [ ] 12.10 **Test Generation**: Use mig-test-gen for post-deploy verification
  - Command: `/skill mig-test-gen` for smoke/E2E against the clustered environment
  - Expected: cluster smoke suite (catalog, cart, checkout, audit) suitable for Tekton
- [ ] 12.11 **Containerization**: freeze the release image and record the digest used in
  production
  - Command: `/skill mig-containerize` for `coolstore` (release freeze)
  - Config needs: digest-pinned references in manifests
- [ ] 12.12 **Deployment**: production rollout with the platform pipeline
  - Command: `/skill mig-deploy` for `coolstore` (prod)
  - Dependencies: all backing services; Network: Route TLS, NetworkPolicies, PDB/HPA
- [ ] 12.13 **Documentation**: final operator runbook (deploy, scale, rotate secrets,
  restore DB, replay DLQ), architecture decision record, and team enablement notes on
  Quarkus/Java 21/OpenShift
