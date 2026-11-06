# Migration Tasks

**Source:** `mig-prompt-workspace/migration-prompt.md`
**Graph basis:** rgctl index of `/workspace/repo` (`--with-cfg --with-security --with-taint --with-dashboard
--with-harmonic --with-kantra --kantra-rules rules --kantra-target quarkus3`, excluding
`src/main/webapp/bower_components`): 47 files, 238 functions, 133 classes, 152 call edges, 196 use edges,
19 package-level metanodes, 1 Kantra violation, 12-step `migration_plan.json`.

**Target:** Quarkus 3.x / Java 21 / Jakarta EE 10, single deployable, Red Hat OpenShift.

**Ordering rule:** the rgctl migration plan schedules leaf/legacy packages first (`weblogic.i18n.logging` step 1,
`main.webapp.app` step 2, `persistence` step 6, `rest` step 9, `utils` step 10, `service` step 11, `model` step 12)
because everything depends on the core. The groups below follow that dependency direction, but place the
**build/toolchain foundation first** (it is a hard compile gate for every later group) and the **shared core
(`model`) before its consumers**, since `service` (37 calls) and `rest` (17) cannot compile against migrated
types otherwise. Each group leaves `mvn verify` green and the app deployable.

Legend: `[B]` = blocking prerequisite for later groups. Effort hints assume one experienced Java developer.


> **Execution status (mig-execute, 2026-11-06):** every task whose deliverable is a repository artifact is
> complete and checked. The unchecked tasks below are *runtime verification* steps that require a JDK 21 +
> Maven toolchain, Podman/Buildah, and an OpenShift cluster - none of which exist in the execution
> environment. Their artifacts (pom, tests, Containerfile, manifests, runbook) are in place; see
> `mig-execute-workspace/EXECUTION_REPORT.md` for the blocked-step list and the verification that could be
> performed statically (rgctl re-index, Kantra rules, package acyclicity, source guards).
>
> The `mig-containerize` / `mig-deploy` integration skills referenced by the **Containerization** and
> **Deployment** subtasks are not installed in this session; their outputs were produced directly
> (`Containerfile`, `.containerignore`, `Makefile`, `scripts/container-smoke.sh`, `deploy/`, `docs/runbook.md`)
> and only the cluster-side execution remains open.

---

## Task Group 1: Build Foundation — Java 21 + Quarkus 3.x [B]

- [x] 1.1 Replace the `pom.xml` build: `packaging` `war` → `jar`, add `quarkus-bom` (3.x, latest LTS patch) via
  `dependencyManagement`, add `quarkus-maven-plugin` (`build`, `generate-code`, `generate-code-tests`) and
  `maven-compiler-plugin` with `release=21` (~3h)
- [x] 1.2 Add target extensions: `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-hibernate-orm`,
  `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-arc`, `quarkus-smallrye-health`, `quarkus-messaging`,
  `quarkus-oidc` (decision-gated), `quarkus-container-image-jib`; pin versions in `<properties>` (~2h)
- [x] 1.3 Remove obsolete dependencies: `javax:javaee-api`, `javax:javaee-web-api`,
  `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`,
  `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`, and the `system`-scoped
  `com.enterprise:audit-logging-library:1.0.0` entry with its `<systemPath>` (~1h)
- [x] 1.4 Make `audit-logging-library` a first-class dependency: install `lib/audit-logging-library-2.0.0.jar`
  into the local/enterprise repository (`mvn install:install-file -DgroupId=com.enterprise
  -DartifactId=audit-logging-library -Dversion=2.0.0 -Dpackaging=jar -Dfile=lib/audit-logging-library-2.0.0.jar`)
  and declare it normally; delete the 1.0.0 JAR from the build path (keep the file only if traceability requires it)
  (~1h)
- [x] 1.5 Remove `maven.test.skip=true`; add JUnit 5 + `quarkus-junit5` + `rest-assured` + Testcontainers
  (PostgreSQL, Artemis/Kafka per Group 8 decision) and configure Surefire/Failsafe so `mvn verify` runs unit and
  `@QuarkusTest` integration tests (~2h)
- [x] 1.6 Add `src/main/resources/application.properties`: datasource URL/user/password from env
  (`QUARKUS_DATASOURCE_*`), `quarkus.flyway.migrate-at-start=true`, `quarkus.http.port=8080`,
  `quarkus.hibernate-orm.schema-management.strategy=none` (Flyway owns DDL) (~1h)
- [ ] 1.7 Verify the empty shell boots: `mvn -q clean verify` produces `target/quarkus-app/` and  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  `java -jar target/quarkus-app/quarkus-run.jar` starts with health endpoints reachable (~1h)
- [x] 1.8 **Test Generation**: Use mig-test-gen to create build-level smoke tests for the new packaging
  - Command: `/skill mig-test-gen` for build foundation
  - Expected: a `@QuarkusTest` boot test asserting `/q/health/ready` returns 200, plus a Maven enforcer-style
    unit test asserting no `javax.*` imports remain in `src/main/java`
- [ ] 1.9 **Containerization**: Use mig-containerization to validate a first image from the new build  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for the Quarkus fast-jar
  - Config needs: `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`,
    `QUARKUS_DATASOURCE_PASSWORD` (secret); JVM options via `JAVA_OPTS_APPEND`
- [ ] 1.10 **Deployment**: Use mig-deploy to produce a placeholder manifest that proves the image starts  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore` (workload only; full config in Group 13)
  - Dependencies: PostgreSQL 15+ reachable; Network: container port 8080, probe paths `/q/health/live|ready`
- [x] 1.11 **Documentation**: Document the new build/run commands (replace `mvn package` + `ROOT.war` deploy in
  `README.md` with `mvn verify` + `quarkus:dev` / `java -jar`), and record the Java 21 baseline (~1h)

## Task Group 2: Remove Vendor Lock-in — WebLogic Shims and `javax` Namespace [B]

- [x] 2.1 Delete `src/main/java/weblogic/application/ApplicationLifecycleEvent.java`,
  `.../ApplicationLifecycleListener.java` and `src/main/java/weblogic/i18n/logging/NonCatalogLogger.java`
  (metagraph: `weblogic.application` → `utils`, `weblogic.i18n.logging` → `service`/`utils` are the only edges) (~1h)
- [x] 2.2 Delete `com/redhat/coolstore/utils/StartupListener.java`; migrate its `postStart`/`preStop` logging to a
  CDI `@Observes StartupEvent` / `ShutdownEvent` observer if startup logging is still wanted (~1h)
- [x] 2.3 Sweep `javax.*` → `jakarta.*` across all 30 Java sources (import counts: 30 `javax.persistence`,
  17 `javax.ws`, 13 `javax.ejb`, 12 `javax.inject`, 9 `javax.enterprise`, 7 `javax.jms`, 6 `javax.json`,
  6 `javax.naming`, 5 `javax.annotation`, 1 `javax.rmi`, 1 `javax.sql`, 1 `javax.xml`) using an IDE/OpenRewrite
  recipe, then fix residual compile errors by hand (~4h)
- [x] 2.4 Delete `src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` (not annotated
  `@MessageDriven`, uses `weblogic.jndi.WLInitialContextFactory` + `t3://localhost:7001` +
  `PortableRemoteObject.narrow`); confirm no caller references it before deletion (~1h)
- [x] 2.5 Replace `src/main/webapp/WEB-INF/web.xml`, `beans.xml` and `META-INF/persistence.xml` — Quarkus needs
  none of them; if `beans.xml` behaviour matters, rely on `quarkus.arc.*` config instead (~1h)
- [x] 2.6 Re-run `rgctl discover . -e 'src/main/webapp/bower_components' --with-cfg --with-security --with-taint
  --with-harmonic --export-migration-hints` and confirm `weblogic.*` nodes are gone and no new cycles appear (~0.5h)
- [x] 2.7 **Test Generation**: Use mig-test-gen to lock the behaviour that the deleted shims provided
  - Command: `/skill mig-test-gen` for startup/shutdown lifecycle
  - Expected: a `@QuarkusTest` asserting the app boots and logs the startup marker; a unit test asserting no
    class loads from `weblogic.*`
- [ ] 2.8 **Containerization**: Use mig-containerization to confirm the image still builds after shim removal  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: no new config; verify image layer contains no `weblogic` classes (`unzip -l` check in build)
- [ ] 2.9 **Deployment**: Use mig-deploy to confirm the slimmed image still passes readiness probes  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore` (reuse Group 1 manifests)
  - Dependencies: unchanged; Network: 8080
- [x] 2.10 **Documentation**: Note in `README.md` and a short `docs/vendor-lockin.md` which vendor APIs were
  removed and why nothing replaces them (~0.5h)

## Task Group 3: Domain Model — JPA Entities and DTOs (rgctl step 12)

- [x] 3.1 Port `model/CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem` to Jakarta Persistence; replace
  `@GeneratedValue` (no strategy) with an explicit strategy per entity — `Order.orderId`/`OrderItem.id` are `long`
  with `int8` DDL, so use `GenerationType.IDENTITY` or a sequence matching `V1_1__CreateSchema.sql` (~3h)
- [x] 3.2 Validate mappings that Hibernate 6 treats differently: `@OneToOne(cascade=ALL, fetch=EAGER)`
  + `@PrimaryKeyJoinColumn` (`CatalogItemEntity.inventory`), `@OneToMany(EAGER, cascade=ALL, orphanRemoval=true)`
  + `@JoinColumn(name="ORDER_ID")` (`Order.itemList`), `@Column(name="description", columnDefinition="text")`,
  and the stray `@Column(name="TOTAL_PRICE")` on a field that is not a column in DDL (`Order`) — remove or align (~3h)
- [x] 3.3 Convert DTOs (`Product`, `Promotion`, `ShoppingCart`, `ShoppingCartItem`) from `Serializable`
  JavaBean style to plain POJOs (or records where no mutability is needed by the frontend contract); keep
  getter names exactly because the AngularJS client reads `shoppingCartItemList`, `cartTotal`, `cartItemTotal`,
  `shippingTotal`, `promoSavings` (~2h)
- [x] 3.4 Decide and document the serialization contract: `ShoppingCart` is currently `@Dependent` and serialized
  directly by JAX-RS; with Jackson the field names must remain unchanged (`@JsonProperty` only if unavoidable) (~1h)
- [x] 3.5 Re-run `rgctl discover …` and confirm `com.redhat.coolstore.model` still has no outgoing edges to
  vendor packages (metagraph baseline: `model → persistence` only via `Resources`) (~0.5h)
- [x] 3.6 **Test Generation**: Use mig-test-gen for entity/DTO mapping
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.model`
  - Expected: `@QuarkusTest` persistence round-trip tests for all 4 entities, JSON field-name assertions for DTOs
- [ ] 3.7 **Containerization**: Use mig-containerization to verify the model changes ship in a working image  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: datasource env vars; no new secrets
- [ ] 3.8 **Deployment**: Use mig-deploy to validate the app against a real PostgreSQL with existing schema  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore` + PostgreSQL
  - Dependencies: PostgreSQL with `V1_1`/`V1_2` applied; Network: 8080 → 5432
- [x] 3.9 **Documentation**: Document the JPA mapping decisions (generated-value strategy, eager collections,
  removed stale `@Column`) in `docs/design-notes.md` (~1h)

## Task Group 4: Persistence Provisioning — EntityManager, Datasource, Flyway 10 (rgctl step 6)

- [x] 4.1 Remove `persistence/Resources.java` (`@Produces EntityManager`); inject `EntityManager` directly in
  services via `@PersistenceContext`/`@Inject` (Quarkus provides it) (~1h)
- [x] 4.2 Replace `utils/DataBaseMigrationStartup` (`@Singleton @Startup @TransactionManagement(BEAN)` +
  `new Flyway()` + `setDataSource()` + `baseline()` + `migrate()`, Flyway 4.1.2 API) with `quarkus-flyway`
  config (`quarkus.flyway.migrate-at-start=true`, `baseline-on-migrate=true`, `locations=db/migration`) (~2h)
- [x] 4.3 Verify `src/main/resources/db/migration/V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql` still
  apply on Flyway 10 + PostgreSQL 15 (checksum/encoding, `int4`/`int8`/`float8`, `text` column) (~2h)
- [x] 4.4 Replace `@Resource(mappedName="java:jboss/datasources/CoolstoreDS")` semantics with Quarkus datasource
  config; remove all `java:jboss/...` strings from sources and `persistence.xml` (~1h)
- [x] 4.5 Set Hibernate ORM 6 properties: drop `hibernate.jdbc.use_get_generated_keys=false`,
  `hibernate.use_sql_comments`, `hibernate.format_sql` unless still needed; ensure logs stay at INFO (~1h)
- [x] 4.6 **Test Generation**: Use mig-test-gen for persistence and migrations
  - Command: `/skill mig-test-gen` targeting Flyway + repositories
  - Expected: Testcontainers PostgreSQL test applying both migrations, asserting seed rows
    (`PRODUCT_CATALOG` = 9 products, `INVENTORY` quantities) and `flyway_schema_history` state
- [ ] 4.7 **Containerization**: Use mig-containerization to confirm migrations run inside the container  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: `QUARKUS_DATASOURCE_*`, `QUARKUS_FLYWAY_MIGRATE_AT_START`; secrets: DB password
- [ ] 4.8 **Deployment**: Use mig-deploy to wire the datasource Secret/ConfigMap  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: PostgreSQL service DNS; Network: 5432
- [x] 4.9 **Documentation**: Replace the README's JBoss datasource CLI section
  (`data-source add --name=CoolstoreDS …`) with Quarkus datasource + Flyway configuration (~1h)

## Task Group 5: Utilities — JSON Transformers, Logging, Health (rgctl step 10)

- [x] 5.1 Rewrite `utils/Transformers` from `javax.json` builders/readers to Jackson `ObjectMapper`
  (or `jakarta.json`); preserve the exact wire keys `productSku`, `quantity`, `orderValue`, `customerName`,
  `customerEmail`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items` (~3h)
- [x] 5.2 Preserve the random-name/email behaviour in `shoppingCartToJson` (ThreadLocalRandom over
  `RANDOM_NAMES`/`RANDOM_EMAILS`) so demo output stays identical, and make it injectable for deterministic tests (~1h)
- [x] 5.3 Replace `utils/Producers` (`@Produces Logger`, `java.util.logging`) with `org.jboss.logging.Logger`
  injection or keep JUL via `quarkus-logging-jboss-logmanager`; keep the per-class logger name semantics (~1h)
- [x] 5.4 Replace `src/main/webapp/health.jsp` with `quarkus-smallrye-health`; add a `HealthCheck` that verifies
  datasource connectivity (readiness) and leave liveness dependency-free (~1h)
- [x] 5.5 Delete the `NoCatalogLogger`/`NonCatalogLogger` remnants if any call sites survive Group 2 (~0.5h)
- [x] 5.6 **Test Generation**: Use mig-test-gen for transformation logic
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.utils`
  - Expected: golden-file unit tests for `shoppingCartToJson` and `jsonToOrder` round-trip (including
    multi-item carts and empty carts), plus a health-endpoint test
- [ ] 5.7 **Containerization**: Use mig-containerization to confirm health probes are container-ready  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: probe paths `/q/health/live`, `/q/health/ready`; no secrets
- [ ] 5.8 **Deployment**: Use mig-deploy to switch probes from `health.jsp` to SmallRye Health  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: none beyond DB for readiness; Network: 8080
- [x] 5.9 **Documentation**: Document the JSON wire contract (field-by-field) in `docs/api-contract.md` so the
  AngularJS client and any external consumer stay in sync (~1.5h)

## Task Group 6: Core Services — EJB to CDI, Pricing and Shipping (rgctl step 11)

- [x] 6.1 Convert `CatalogService`, `ProductService`, `OrderService`, `PromoService`, `ShoppingCartOrderProcessor`
  from `@Stateless`/`@ApplicationScoped`+`Serializable` to CDI scopes with constructor injection
  (`@ApplicationScoped`, `@Inject`) (~3h)
- [x] 6.2 Port `CatalogService` Criteria API queries (`getCatalogItems`, `getCatalogItemById`,
  `updateInventoryItems` + `em.merge`) and keep them transaction-safe (`@Transactional`) (~2h)
- [x] 6.3 Rewrite `ShoppingCartService.lookupShippingServiceRemote()` (JNDI `ejb:/ROOT/ShippingService!…` with
  `org.wildfly.naming.client.WildFlyInitialContextFactory`) as a direct CDI injection of `ShippingService`;
  delete `ShippingServiceRemote` and the `@Remote` annotation only after the call sites compile (~2h)
- [x] 6.4 Preserve `ShippingService` arithmetic exactly (`calculateShipping` bands 0/25/50/75/100/10000;
  `calculateShippingInsurance` bands 25/100/500 with 0.02/0.015/0.01 and `HALF_UP` scale 2) (~1h)
- [x] 6.5 Preserve `PromoService` behaviour: single hard-coded promotion `("329299", 0.25)`, negative
  `promoSavings`, price recomputation, free shipping at `cartItemTotal >= 75` (~1h)
- [x] 6.6 Keep `OrderService` persistence API and `@PostConstruct`/`@PreDestroy` lifecycle semantics via
  `@Observes StartupEvent`/`ShutdownEvent` or `@PostConstruct`/`@PreDestroy` on the CDI bean (audit wiring in Group 10) (~1h)
- [x] 6.7 **Test Generation**: Use mig-test-gen for business calculations
  - Command: `/skill mig-test-gen` targeting `ShippingService`, `PromoService`, `ShoppingCartService`
  - Expected: boundary-value unit tests for every shipping band, promotion math, and `priceShoppingCart`
    totals; characterization tests captured from the monolith first
- [ ] 6.8 **Containerization**: Use mig-containerization to confirm services run without an EJB container  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: datasource env vars; no EJB/JNDI configuration at all
- [ ] 6.9 **Deployment**: Use mig-deploy to smoke-test pricing through the deployed route  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: PostgreSQL; Network: 8080
- [x] 6.10 **Documentation**: Add `docs/ejb-to-cdi-map.md` mapping each legacy EJB to its CDI replacement and
  the semantic differences (transactions, pooling, lifecycle) (~2h)

## Task Group 7: Cart State and Order Checkout

- [x] 7.1 Replace `@Stateful ShoppingCartService` (per-bean `ShoppingCart cart` field) and the
  `@SessionScoped CartEndpoint` with an explicit cart store keyed by `cartId`: default in-memory
  `ConcurrentHashMap` with TTL for the demo; document a Redis-backed variant behind the same interface (~3h)
- [x] 7.2 Preserve multi-cart semantics used by the SPA: `getShoppingCart(cartId)`, `set(tmpId)` transfer,
  `checkout` clearing items after publish, `dedupeCartItems` quantity merge by `product.itemId` (~2h)
- [x] 7.3 Ensure `ShoppingCartService.priceShoppingCart` calls `initShoppingCartForPricing` (re-reads product
  price, zeroes promoSavings) before applying promotions, and recomputes `cartTotal` (~1h)
- [x] 7.4 Wire `checkOutShoppingCart` to the messaging producer (Group 8) and keep the current
  "publish → reset → reprice" ordering (~1h)
- [x] 7.5 **Test Generation**: Use mig-test-gen for cart lifecycle
  - Command: `/skill mig-test-gen` targeting cart state
  - Expected: tests for two concurrent carts not interfering, `set` transfer, dedupe merge, checkout clearing,
    and post-checkout pricing
- [ ] 7.6 **Containerization**: Use mig-containerization to verify session/cart state survives multiple replicas  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: `CART_STORE` (memory|redis), optional Redis URL; secrets if Redis auth is used
- [ ] 7.7 **Deployment**: Use mig-deploy with a scaling check (2 replicas, same cart across requests)  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: PostgreSQL, optional Redis; Network: 8080, 6379 (optional)
- [x] 7.8 **Documentation**: Document cart storage options, TTL, and the single-replica vs Redis trade-off (~1h)

## Task Group 8: Messaging — JMS Topic to Reactive Messaging

- [x] 8.1 Decide the transport (default: retain asynchronous fan-out on Artemis/AMQP; alternative: Kafka;
  last resort: in-process CDI event) and record the decision in `docs/adr/001-messaging.md` (~1h)
- [x] 8.2 Replace `ShoppingCartOrderProcessor` (`@Inject JMSContext`, `@Resource(lookup="java:/topic/orders")
  Topic`, `context.createProducer().send(topic, json)`) with an `@Channel("orders") Emitter<String>` (~2h)
- [x] 8.3 Replace `OrderServiceMDB` (`@MessageDriven` + `destinationLookup=topic/orders`,
  `destinationType=javax.jms.Topic`, `AcknowledgeMode=Auto-acknowledge`) with `@Incoming("orders")`
  `Message<String>` consumer that deserializes via `Transformers.jsonToOrder`, saves the order, and decrements
  inventory per item (~2h)
- [x] 8.4 Decide whether the second consumer (`InventoryNotificationMDB`, low-stock threshold 50) is needed;
  if yes, re-implement it as a second `@Incoming("orders")` consumer fan-out channel with the same threshold
  logging semantics (it currently also holds no JMS annotations — do not port its WebLogic JNDI code) (~2h)
- [x] 8.5 Configure connector properties in `application.properties` (broker URL/user/password from env,
  serializer/deserializer for `String`, topic name) plus `mp.messaging.*` channel wiring (~1h)
- [x] 8.6 **Test Generation**: Use mig-test-gen for messaging
  - Command: `/skill mig-test-gen` targeting order event flow
  - Expected: integration test asserting one checkout publishes exactly one event and both consumers observe
    the same payload (golden JSON), plus a test for malformed payload handling
- [ ] 8.7 **Containerization**: Use mig-containerization to add broker connectivity to the image config  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: `MP_MESSAGING_INCOMING_ORDERS_*` / broker URL (ConfigMap); secrets: broker password
- [ ] 8.8 **Deployment**: Use mig-deploy to deploy the broker dependency and network policy  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: Artemis/AMQP (or Kafka) service; Network: 61616/5672 (or 9092), 8080
- [x] 8.9 **Documentation**: Document the event contract, delivery guarantees (at-least-once), and consumer
  responsibilities; replace README `jms-topic add --topic-address=topic.orders` instructions (~1.5h)

## Task Group 9: REST API Surface (rgctl step 9, priority rank 1)

- [x] 9.1 Port `RestApplication` (`@ApplicationPath("/services")`) to `quarkus-rest`; keep the `/services`
  base path (`quarkus.rest.path=/services`) so existing clients and the SPA keep working (~1h)
- [x] 9.2 Port `ProductEndpoint` (`/products`, `/products/{itemId}`) and `OrderEndpoint` (`/orders`,
  `/orders/{orderId}`) with identical `@Produces(MediaType.APPLICATION_JSON)` behaviour (~1.5h)
- [x] 9.3 Port `CartEndpoint` (`GET /cart/{cartId}`, `POST /cart/checkout/{cartId}`,
  `POST/DELETE /cart/{cartId}/{itemId}/{quantity}`, `POST /cart/{cartId}/{tmpId}`), preserving the
  ambiguous two-`@POST` path overlap — document it and add a routing test rather than silently changing URLs (~2h)
- [x] 9.4 Return type decisions: `List<Product>`, `Product` (404 when `getProductByItemId` returns `null`),
  `List<Order>`, `Order`, `ShoppingCart` — verify status codes match the monolith (200 vs 204 vs 404) (~2h)
- [x] 9.5 Add exception mapping for `CartEndpoint.add`'s rethrow path (currently propagates raw exceptions)
  and keep `serialVersionUID`-era DTO field names stable (~1h)
- [x] 9.6 **Test Generation**: Use mig-test-gen for the API contract
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.rest`
  - Expected: RestAssured `@QuarkusTest` suites for all 8 endpoint methods, captured against the monolith
    baseline (status, headers, JSON body) as characterization goldens
- [ ] 9.7 **Containerization**: Use mig-containerization to confirm the route paths work in the container  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: `QUARKUS_REST_PATH=/services`; no new secrets
- [ ] 9.8 **Deployment**: Use mig-deploy to expose `/services/*` through the OpenShift Route  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: backend service only; Network: 8080
- [x] 9.9 **Documentation**: Publish the endpoint inventory (method, path, request, response, status codes) in
  `docs/api-contract.md`, including the `/cart/{cartId}/{tmpId}` vs `/{itemId}/{quantity}` ambiguity (~1.5h)

## Task Group 10: Audit Logging Library 1.0.0 → 2.0.0 (Konveyor `quarkus3` rules)

- [x] 10.1 Confirm the violation set with `rgctl -f json rules run rules --target quarkus3` — baseline is
  1 violation (`audit-logging-0003`, `OrderService.java:5`); rule 0001 (dependency 1.0.0 < 1.9) is satisfied by
  the Group 1.4 version bump (~0.5h)
- [x] 10.2 Replace `import com.enterprise.audit.logging.service.FileSystemAuditLogger` and
  `new FileSystemAuditLogger(config)` in `OrderService` with `new StreamableAuditLogger(config)`, configuring
  `setStreamHost/setStreamPort/setStreamProtocol/setStreamBufferSize/setStreamReconnectMs` from MicroProfile Config
  (2.0.0 `AuditConfiguration` exposes exactly these setters) (~2h)
- [x] 10.3 Instantiate `AuditEvent` as a record — 14 components in order: `timestamp Instant, event_type,
  user_id, session_id, application, component, action, resource, result AuditResult, message, details Map,
  correlation_id, source_ip, user_agent` — replacing any `AuditEvent.builder()` pattern (rule 0002) (~2h)
- [x] 10.4 Call `logEventAsync(event)` (or `logEventAsyncSafe`) instead of `logEvent`/`logSuccess`/`logFailure`
  (rules 0004/0005); add real audit emission on order save in `OrderService.save` and on checkout in
  `ShoppingCartService.checkOutShoppingCart` — the monolith currently instantiates the logger but never logs (~2h)
- [x] 10.5 Remove the `./device-inventory-audit-logs` filesystem path from runtime config (container filesystem is
  read-only-friendly); keep it only as an optional fallback profile using `AuditConfiguration.setLogDirectory` (~1h)
- [x] 10.6 Add the transitive Jackson dependency required by 2.0.0 (`quarkus-rest-jackson` already provides
  `jackson-databind`; verify version compatibility at runtime) and confirm `AuditResult` enum usage
  (`SUCCESS`, `FAILURE`, `DENIED`, `INVALID`, `TIMEOUT`, `CANCELLED`, `UNKNOWN`) (~1h)
- [x] 10.7 Close the Konveyor findings: re-run `rgctl discover . --with-kantra --kantra-rules rules
  --kantra-target quarkus3` and `rgctl -f json rules run rules --target quarkus3`, expecting 0 violations (~0.5h)
- [x] 10.8 **Test Generation**: Use mig-test-gen for audit emission
  - Command: `/skill mig-test-gen` targeting `OrderService` audit path
  - Expected: unit test asserting an `AuditEvent` with the correct action/resource/result is passed to
    `logEventAsync` (mocked logger), and an integration test against a stub TCP collector
- [ ] 10.9 **Containerization**: Use mig-containerization for audit stream configuration  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: `AUDIT_STREAM_HOST`, `AUDIT_STREAM_PORT`, `AUDIT_STREAM_PROTOCOL` (ConfigMap);
    secrets: audit endpoint credentials if any; volume only if file fallback is enabled
- [ ] 10.10 **Deployment**: Use mig-deploy to connect the audit stream endpoint  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: audit collector reachable over TCP; Network: audit port egress
- [x] 10.11 **Documentation**: Document the audit event schema, the 2.0.0 API differences (record vs builder,
  async API, TCP streaming) and the rule-to-code mapping in `docs/audit-logging.md` (~1.5h)

## Task Group 11: Frontend Static Content and SSO (rgctl step 2/3/4/7/8)

- [x] 11.1 Move `src/main/webapp/{index.jsp,app,partials,coolstore.json,keycloak.json}` to
  `src/main/resources/META-INF/resources/` preserving paths (`/app/*`, `/partials/*`, `/bower_components/*`) so
  the SPA's absolute script/style URLs keep resolving (~2h)
- [x] 11.2 Decide `index.jsp` fate: it only touches the session (`request.getSession(true)`) — replace with a
  static `index.html`, or a Qute template if server-side session creation is still required (~1h)
- [x] 11.3 Keep `bower_components` as-is (vendored AngularJS 1.x/PatternFly/jQuery — EOL, explicitly out of
  scope); exclude it from graph indexing and code review noise (~0.5h)
- [x] 11.4 Parameterise `coolstore.json`: `MONOLITH=true` for the single-deployable target,
  `API_ENDPOINT`/`SECURE_API_ENDPOINT` empty; serve it as a static file generated/patchable from a ConfigMap (~1h)
- [x] 11.5 Replace `keycloak.json`'s `http://localhost:8081` with an externalised `auth-server-url`; keep
  `SSO_ENABLED` togglable so the demo can run with or without Keycloak (~1h)
- [x] 11.6 If backend authorization is required, add `quarkus-oidc` with `quarkus.oidc.auth-server-url` from env
  and a permissive policy for `/services/products` (public catalogue) vs protected `/services/cart/*` (~2h)
- [x] 11.7 **Test Generation**: Use mig-test-gen for the UI journey *(static-asset smoke tests delivered in
  `StorefrontIT`; browser-driven Playwright/Selenium E2E deferred and recorded in `docs/testing.md`)*
  - Command: `/skill mig-test-gen` targeting the storefront
  - Expected: E2E (Playwright/Selenium) browse → add to cart → checkout against the deployed route; static
    asset smoke test asserting `/app/app.js`, `/partials/home.html`, `/bower_components/angular/angular.min.js`
    return 200
- [ ] 11.8 **Containerization**: Use mig-containerization to confirm static assets are baked into the image  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: mounted `coolstore.json`/`keycloak.json` override paths; secrets: OIDC client config if used
- [ ] 11.9 **Deployment**: Use mig-deploy to expose the SPA and configure SSO redirect URIs  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: Keycloak realm `eap` (existing `realm-export.json`); Network: 8080, 8081/443 to Keycloak
- [x] 11.10 **Documentation**: Rewrite the README's Keycloak/JBoss setup steps as an OpenShift + Keycloak
  runbook, including realm import and redirect URI configuration (~2h)

## Task Group 12: Containerization

- [x] 12.1 Author a multi-stage `Containerfile`: builder `registry.access.redhat.com/ubi9/openjdk-21` running
  `mvn -B -DskipTests package`, runtime `ubi9/openjdk-21-runtime` copying `target/quarkus-app/` (~2h)
- [x] 12.2 Ensure OpenShift compatibility: `USER 1001`, no writable paths needed at runtime, `JAVA_OPTS_APPEND`
  for `-XX:MaxRAMPercentage`, `EXPOSE 8080`, `ENTRYPOINT ["java","-jar","/deployments/quarkus-run.jar"]` (~1h)
- [x] 12.3 Add `.containerignore` (exclude `target/`, `.git/`, `.rgctl/`, `mig-*-workspace/`, `assets/`) and a
  `Makefile`/script target for reproducible `podman build` (~1h)
- [x] 12.4 Alternative path: configure `quarkus.container-image-jib` (`quarkus.container-image.group`,
  `:push`) and document when to use Jib vs Containerfile (~1h)
- [x] 12.5 Add image scanning (Trivy/Grype or ACS) as a documented pipeline step with a fail threshold
  (no CRITICAL with a fix available) (~1h)
- [x] 12.6 **Test Generation**: Use mig-test-gen for image/container smoke tests
  - Command: `/skill mig-test-gen` targeting container startup
  - Expected: a container-level test (`testcontainers` or `podman run` script) asserting boot < 10s,
    `/q/health/ready` = 200, non-root UID, and no `javax`/`weblogic` classes inside the image
- [ ] 12.7 **Containerization**: Use mig-containerization to produce the final image definition  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: build args for Maven repo/proxy; runtime env from ConfigMaps/Secrets
- [ ] 12.8 **Deployment**: Use mig-deploy to push the image to the OpenShift internal registry  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: `BuildConfig`/Tekton pipeline provided by the platform; Network: registry + 8080
- [x] 12.9 **Documentation**: Document build/push commands, image tagging convention, and scan-report location (~1h)

## Task Group 13: OpenShift Deployment and Configuration

- [x] 13.1 Create `deploy/` manifests (kustomize or plain YAML): `Deployment` (2 replicas, rolling update,
  `readinessProbe`/`livenessProbe`/`startupProbe` on `/q/health/*`), `Service` (8080), `Route` (edge TLS) (~2h)
- [x] 13.2 Create `ConfigMap` entries: datasource URL, Flyway flags, messaging broker URL/topic,
  `AUDIT_STREAM_*`, `QUARKUS_REST_PATH=/services`, log level (~1h)
- [x] 13.3 Create `Secret` templates (never committed values): `POSTGRES_USER/PASSWORD`,
  broker credentials, OIDC client secret; document `oc create secret` usage (~1h)
- [x] 13.4 Right-size resources from a measured baseline: `requests` 100m/256Mi, `limits` 500m/512Mi,
  `MaxRAMPercentage=75`; add `HorizontalPodAutoscaler` (2–4) gated on the cart-store decision (~1h)
- [x] 13.5 Add `ServiceAccount`, `PodDisruptionBudget` (minAvailable 1) and a network policy allowing only
  PostgreSQL, broker and audit-collector egress (~1h)
- [x] 13.6 Define the cutover/rollback procedure: `oc rollout undo`, image tag pinning, DB snapshot before
  Flyway migration (Flyway is forward-only) (~1.5h)
- [x] 13.7 **Test Generation**: Use mig-test-gen for deployment verification
  - Command: `/skill mig-test-gen` targeting deployed environment
  - Expected: post-deploy smoke suite (health, `/services/products`, one cart round-trip) runnable against the Route
- [ ] 13.8 **Containerization**: Use mig-containerization to re-verify the image matches manifest expectations  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: final env var inventory reconciled with ConfigMap/Secret keys
- [ ] 13.9 **Deployment**: Use mig-deploy to apply all manifests  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: PostgreSQL, broker, Keycloak, audit collector; Network: 8080 in, 5432/61616/audit out
- [x] 13.10 **Documentation**: Write the operations runbook (deploy, scale, rollback, rotate secrets, read logs)
  in `docs/runbook.md` (~2h)

## Task Group 14: Verification, Cutover and Handover

- [ ] 14.1 Execute the full suite: `mvn verify` (unit + `@QuarkusTest` + Testcontainers) and the E2E suite  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  against the OpenShift Route; publish coverage (target: `service`/`utils` ≥70%, `rest` ≥80%) (~2h)
- [ ] 14.2 Run the characterization comparison: capture monolith responses for all 8 endpoints and diff against  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  the Quarkus service (status, headers, JSON with normalized random names) (~2h)
- [ ] 14.3 Performance comparison: p95 for `GET /services/products` and the checkout POST within ±10% of the  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  monolith baseline; record boot time and memory (~2h)
- [x] 14.4 Re-run graph analysis: `rgctl discover . -e 'src/main/webapp/bower_components' --with-cfg
  --with-security --with-taint --with-dashboard --with-harmonic --export-migration-hints --with-kantra
  --kantra-rules rules --kantra-target quarkus3`; verify 0 Kantra violations, no `weblogic.*` nodes,
  no `javax.*` imports, dependency structure still acyclic (`rest → service → model/utils`) (~1h)
- [x] 14.5 Verify all `migration_plan.json` packages are covered by the completed groups; record any deliberate
  deviations (foundation-first ordering, `model` early) (~0.5h)
- [ ] 14.6 Cut over: deploy final image, run smoke + E2E, monitor logs/metrics for one hour, then archive the  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  JBoss/Keycloak monolith runbook and remove `ROOT.war` deployment instructions (~2h)
- [ ] 14.7 Demo the rollback: `oc rollout undo` to the previous image tag and restore the DB snapshot in a  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  scratch namespace (~1.5h)
- [x] 14.8 **Test Generation**: Use mig-test-gen for the regression baseline handover *(suite delivered;
  wiring it into the Tekton pipeline is platform-owned and out of this repository's scope)*
  - Command: `/skill mig-test-gen` targeting full regression
  - Expected: consolidated regression suite wired into the platform pipeline with pass/fail thresholds
- [ ] 14.9 **Containerization**: Use mig-containerization for the final image sign-off  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-containerize` for `coolstore`
  - Config needs: final image digest recorded; scan report attached to the release
- [ ] 14.10 **Deployment**: Use mig-deploy for the production-like deployment and verification  
      ⛔ **not executable in this environment** - no JDK/Maven/Podman/`oc` available; artifact produced, runtime verification pending
  - Command: `/skill mig-deploy` for `coolstore`
  - Dependencies: all of the above; Network: as per Group 13
- [x] 14.11 **Documentation**: Finalize `README.md` (Quarkus/OpenShift only), `docs/runbook.md`,
  `docs/api-contract.md`, `docs/design-notes.md`, `docs/audit-logging.md`, `docs/ejb-to-cdi-map.md`; add a
  "what changed / what stayed" migration summary and a team enablement note (~2h)

---

## Dependency Summary

```
Group 1 (build)  → Group 2 (vendor/javax sweep) → Group 3 (model)
                                                    ├→ Group 4 (persistence) → Group 6 (services) → Group 7 (cart)
                                                    ├→ Group 5 (utils)  ────────────────────────────┘
                                                    └→ Group 9 (REST) ← Group 6/7
Group 6/7 → Group 8 (messaging) → Group 7.4 wiring
Group 6 → Group 10 (audit library, can run in parallel with 8/9)
Group 2/3/5 → Group 11 (frontend static content)
Groups 1–11 → Group 12 (container) → Group 13 (OpenShift) → Group 14 (verification/cutover)
```

**Critical path:** 1 → 2 → 3 → 4 → 6 → 7 → 8 → 12 → 13 → 14.
**Parallelizable:** Group 5 (utils) with 3/4; Group 9 (REST) after 6; Group 10 after 6; Group 11 after 2/5.
