# Migration Tasks

**Project**: CoolStore Monolith (`com.redhat.coolstore:monolith`)
**Migration**: Java EE 7 on JBoss EAP 7.4 → Quarkus 3.20 (Jakarta EE 10) on Java 21, deployed to OpenShift
**Foundation**: `mig-prompt-workspace/migration-prompt.md` (specification + design)
**Knowledge graph**: `.rgctl/` snapshot (47 files, 971 nodes, 2,544 edges, 238 functions, 812 communities)

Task groups follow the rgctl Hybrid-Default roadmap (12 communities) collapsed into dependency-ordered waves:
foundation → model/persistence → services → messaging → REST → static content → audit library → tests → container → deploy.
`[P]` marks work that can run in parallel with its siblings.

## Task Group 1: Build foundation and project skeleton

- [x] 1.1 Rewrite `pom.xml`: `quarkus.platform.version` 3.20.x BOM, `maven.compiler.release=21`, packaging `jar`,
      `quarkus-maven-plugin` (`build`, `generate-code`, `generate-code-tests`), `maven-surefire`/`failsafe`,
      `quarkus.package.jar.type=fast-jar`
- [x] 1.2 Replace Java EE dependencies with Quarkus extensions: `quarkus-resteasy-jackson` (RESTEasy Classic + Jackson),
      `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-health`,
      `quarkus-smallrye-openapi`, `quarkus-smallrye-reactive-messaging-kafka`, `quarkus-jackson`; test scope:
      `quarkus-junit5`, `rest-assured`, `quarkus-jdbc-h2`, `quarkus-test-kafka-companion` (or `kafka-companion`)
- [x] 1.3 Swap the system-scoped audit jar from `lib/audit-logging-library-1.0.0.jar` to
      `lib/audit-logging-library-2.0.0.jar` (keep system scope + `lib/` checked in; document why no repo coordinates exist)
- [x] 1.4 Delete Java EE scaffolding: `src/main/webapp/WEB-INF/web.xml`, `beans.xml`, `src/main/resources/META-INF/persistence.xml`
- [x] 1.5 Delete WebLogic shims (`src/main/java/weblogic/**`) and `utils/StartupListener.java` (dead vendor code, 0 callers)
- [x] 1.6 Create `src/main/resources/application.properties` skeleton: datasource (env-overridable), Hibernate ORM,
      Flyway, HTTP port 8080, health, Kafka channels, audit stream settings, `quarkus.http.root-path=/`
- [x] 1.7 **Documentation**: record the target stack and build prerequisites in `README.md`
  - Note the Java 21 + Maven requirement and the removal of the EAP/JBoss CLI workflow

## Task Group 2: Persistence layer (JPA 2.1 → Jakarta Persistence 3.1)

- [x] 2.1 Migrate the four entities to `jakarta.persistence` (`CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem`);
      keep table/column names byte-identical so the Flyway schema still matches
- [x] 2.2 Fix `Order` mapping defects surfaced by Hibernate 6: remove the stray `@Column(name="TOTAL_PRICE")` on the
      `itemList` association; make `@GeneratedValue` use the legacy `hibernate_sequence` via explicit
      `@SequenceGenerator` so inserts work against the existing schema
- [x] 2.3 Remove `javax.xml.bind.annotation.XmlRootElement` from `InventoryEntity` (JSON is Jackson-based; JAXB is gone)
- [x] 2.4 Delete `persistence/Resources.java` (`@PersistenceContext` producer) — inject `EntityManager` directly in
      `CatalogService`/`OrderService` (Quarkus request-scoped `EntityManager`)
- [x] 2.5 Convert `CatalogService`/`OrderService`/`ProductService` from `@Stateless` EJB to `@ApplicationScoped` CDI,
      replace the Criteria API boilerplate with JPQL `select e from …` queries, add `@Transactional` to mutating methods
- [x] 2.6 Replace `DataBaseMigrationStartup` (Flyway 4 `new Flyway()`) with Quarkus Flyway:
      `quarkus.flyway.migrate-at-start=true`, `baseline-on-migrate=true`, existing `db/migration/V1_1`, `V1_2` kept as-is
- [x] 2.7 **Test Generation**: Use mig-test-gen for the persistence layer
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.persistence` + entity layer
  - Expected: entity mapping tests (schema/column names, sequence generation), repository query tests against H2
- [x] 2.8 **Documentation**: document schema ownership (Flyway is authoritative; Hibernate schema generation disabled)

## Task Group 3: Domain services (EJB 3.2 → CDI beans)

- [x] 3.1 Convert `PromoService` → `@ApplicationScoped` (already CDI-annotated; drop `Serializable` ceremony), keep
      promotion rule (25% off item `329299`) and free-shipping rule (cart item total ≥ 75)
- [x] 3.2 Convert `ShippingService`/`ShippingServiceRemote` → single `@ApplicationScoped` `ShippingService`; delete the
      `@Remote` interface and the WildFly JNDI lookup (`ejb:/ROOT/ShippingService!…`) in `ShoppingCartService`
- [x] 3.3 Replace the `@Stateful` `ShoppingCartService` with an `@ApplicationScoped` service plus a `CartStore`
      (`ConcurrentHashMap<String, ShoppingCart>`) keyed by `cartId`; preserve `getShoppingCart`, add/transfer/delete,
      `priceShoppingCart`, `checkOutShoppingCart` behaviour
- [x] 3.4 Preserve pricing algorithm exactly: reset totals, re-price items from catalog, apply item promos, accumulate
      `cartItemPromoSavings`, add shipping bands 2.99/4.99/6.99/8.99/10.99 and insurance 2%/1.5%/1% for totals ≥ 25,
      apply free-shipping promo, compute `cartTotal`
- [x] 3.5 Replace `java.util.logging`/CDI `Logger` producer with `org.jboss.logging.Logger` injection
      (`@Inject Logger`), delete `utils/Producers.java`, map `warning`→`warn`, `Level.SEVERE`→`error`
- [x] 3.6 **Test Generation**: Use mig-test-gen for domain logic
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.service`
  - Expected: unit tests for `ShippingService` (all bands incl. boundaries 0/25/50/75/100/10000), `PromoService`
    (item + shipping promos), `ShoppingCartService` (dedupe, transfer, delete partial quantity, checkout reset)
- [x] 3.7 **Documentation**: document the cart-state behaviour change (node-local `@Stateful` → store keyed by `cartId`)

## Task Group 4: Messaging (JMS topic → Kafka, MDB → Reactive Messaging)

- [x] 4.1 Configure channels in `application.properties`: outgoing `orders-out` → topic `orders`; incoming
      `orders-in` (group `order-service`) and `orders-inventory` (group `inventory-notification`) both on topic `orders`
      so topic fan-out semantics are preserved
- [x] 4.2 Convert `ShoppingCartOrderProcessor` to inject `@Channel("orders-out") Emitter<String>` and emit the order JSON
      (drop `JMSContext`/`@Resource(lookup="java:/topic/orders")`)
- [x] 4.3 Replace `OrderServiceMDB` (`@MessageDriven`) with `OrderMessageConsumer` using
      `@Incoming("orders-in")`, `@Transactional`: deserialize, `orderService.save(order)`, decrement inventory per item
- [x] 4.4 Replace `InventoryNotificationMDB` (WebLogic T3 JNDI, never actually deployed) with
      `InventoryNotificationConsumer` using `@Incoming("orders-inventory")`: log low-stock warnings when
      projected quantity < 50, otherwise update the in-memory item quantity exactly as before
- [x] 4.5 Serialize/deserialize order messages with Jackson (`ObjectMapper`) instead of `javax.json` (JSON-P), keeping the
      wire format identical: `orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`,
      `shippingDiscount`, `items[{productSku,quantity}]`
- [x] 4.6 **Test Generation**: Use mig-test-gen for the messaging pipeline
  - Command: `/skill mig-test-gen` targeting `ShoppingCartOrderProcessor`, `OrderMessageConsumer`,
    `InventoryNotificationConsumer`, `Transformers`
  - Expected: JSON round-trip tests, consumer tests asserting order persistence + inventory decrement, low-stock path
- [x] 4.7 **Deployment**: Use mig-deploy for the Kafka dependency
  - Command: `/skill mig-deploy` for the `orders` topic
  - Dependencies: AMQ Streams / Strimzi Kafka cluster; `KafkaTopic` CR for `orders`
  - Network: bootstrap `kafka:9092` via ConfigMap/Secret

## Task Group 5: REST layer (JAX-RS 2.0 → Jakarta REST 3.1 on Quarkus)

- [x] 5.1 Migrate `javax.ws.rs` → `jakarta.ws.rs` in `RestApplication`, `ProductEndpoint`, `CartEndpoint`, `OrderEndpoint`
- [x] 5.2 Freeze the URL contract: `@ApplicationPath("/services")` retained (Quarkus RESTEasy Classic honours it);
      verify `/services/products`, `/services/products/{itemId}`, `/services/cart/{cartId}`,
      `POST|DELETE /services/cart/{cartId}/{itemId}/{quantity}`, `POST /services/cart/{cartId}/{tmpId}`,
      `POST /services/cart/checkout/{cartId}`, `/services/orders`, `/services/orders/{orderId}`
- [x] 5.3 Replace `@SessionScoped`/`@RequestScoped` resource scoping with Quarkus singletons delegating to the
      `cartId`-keyed store; remove `Serializable`/`serialVersionUID` ceremony from resources
- [x] 5.4 Add explicit JSON media types and null-safe handling for unknown `itemId`/`cartId`
      (`ProductEndpoint` returns `null` → 204 today; keep behaviour, document it)
- [x] 5.5 **Test Generation**: Use mig-test-gen for the HTTP contract
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.rest`
  - Expected: `@QuarkusTest` + RestAssured characterization tests for every endpoint and JSON field name
- [x] 5.6 **Documentation**: publish the frozen API contract (paths, verbs, payload shapes) in `README.md`

## Task Group 6: Static front end and health endpoints

- [x] 6.1 Move `src/main/webapp/{app,bower_components,partials,coolstore.json,keycloak.json}` to
      `src/main/resources/META-INF/resources/` verbatim (Quarkus serves static content from there)
- [x] 6.2 Convert `index.jsp` → `index.html`, dropping the `<% request.getSession(true); %>` scriptlet (no JSP engine;
      the SPA manages `cartId` in `localStorage` and Keycloak handles the session)
- [x] 6.3 Replace `health.jsp` with SmallRye Health: `/q/health`, `/q/health/live`, `/q/health/ready`; enable
      `/q/metrics` and `/q/openapi`
- [x] 6.4 Keep `COOLSTORE_CONFIG.MONOLITH=true` so the AngularJS services keep calling `/services/*` relatively
- [x] 6.5 **Documentation**: note the static-content relocation and that JSP is no longer supported

## Task Group 7: audit-logging-library 1.0.0 → 2.0.0 (Konveyor mandatory rules)

- [x] 7.1 Satisfy `audit-logging-0001`: depend on `audit-logging-library:2.0.0` and compile with Java 21
- [x] 7.2 Satisfy `audit-logging-0003`: replace `new FileSystemAuditLogger(config)` with
      `new StreamableAuditLogger(config)` configured for TCP (`setStreamHost`, `setStreamPort`, `setStreamProtocol`)
      with host/port from configuration (`audit.stream.host`, `audit.stream.port`)
- [x] 7.3 Satisfy `audit-logging-0002`/`0004`/`0005`: construct `AuditEvent` records directly (14 components:
      `timestamp`, `event_type`, `user_id`, `session_id`, `application`, `component`, `action`, `resource`, `result`,
      `message`, `details`, `correlation_id`, `source_ip`, `user_agent`) and publish with non-blocking
      `logEventAsync(...)`; no `logEvent`/`logSuccess`/`logFailure` calls
- [x] 7.4 Emit an audit event on the order-persistence path (`action=SAVE_ORDER`, `resource=orders/{id}`,
      `result=SUCCESS|FAILURE`) and close the logger in `@PreDestroy`
- [x] 7.5 Guard the audit path so an unavailable TCP collector cannot fail checkout (`logEventAsyncSafe`/try-catch,
      non-blocking executor)
- [x] 7.6 **Test Generation**: Use mig-test-gen for the audit integration
  - Command: `/skill mig-test-gen` targeting `com.redhat.coolstore.audit`
  - Expected: configuration binding tests, `AuditEvent` record construction tests, failure-tolerant publish test
- [x] 7.7 **Documentation**: document the audit stream contract (TCP host/port, JSON event schema) and re-run
      `rgctl rules run rules` to confirm 0 violations

## Task Group 8: Test suite and behaviour verification

- [x] 8.1 Characterization tests for pricing/shipping/promotions copied from observed Java EE behaviour (the current
      `maven.test.skip=true` means no tests exist — this is greenfield coverage of legacy behaviour)
- [x] 8.2 `@QuarkusTest` integration tests: product catalog read path (seed data from `V1_2__AddInitialData.sql`),
      cart add/remove/transfer/checkout, order listing after checkout
- [x] 8.3 Messaging tests: JSON round-trip and consumer behaviour (persist + inventory decrement, low-stock threshold 50)
- [x] 8.4 Enable tests in the build (`maven.test.skip` removed, surefire/failsafe wired) and document the
      `mvn verify` gate
- [x] 8.5 Record coverage intent and known gaps (no end-to-end browser test, no cluster performance test)
- [x] 8.6 **Documentation**: add a "Testing" section to `README.md` (unit, `@QuarkusTest`, Kafka companion)

## Task Group 9: Containerization

- [x] 9.1 Create multi-stage `Containerfile` (UBI 9 + OpenJDK 21 builder → `ubi9/openjdk-21-runtime`, non-root UID 185,
      `quarkus-run.jar` entrypoint, `JAVA_OPTS_APPEND`/`JAVA_MAX_MEM_RATIO` friendly)
- [x] 9.2 Add `.dockerignore` (exclude `target/`, `.git/`, `.rgctl/`, `lib/*.jar` only if vendored elsewhere)
- [x] 9.3 Verify image runs as non-root, exposes 8080, has no secrets in layers, and uses a health-aware start command
- [x] 9.4 **Containerization**: Use mig-containerize for the application
  - Command: `/skill mig-containerize` for the Quarkus monolith (`com.redhat.coolstore:monolith`)
  - Config needs: `QUARKUS_DATASOURCE_*` (Secret), `KAFKA_BOOTSTRAP_SERVERS` (ConfigMap), `AUDIT_STREAM_*` (ConfigMap)
- [x] 9.5 **Documentation**: document `podman build`/`podman run` with a local PostgreSQL/Kafka compose recipe

## Task Group 10: OpenShift deployment

- [x] 10.1 Create `deploy/openshift/` manifests: `Deployment` (probes `/q/health/live|ready`, resources,
      `runAsNonRoot`, `allowPrivilegeEscalation: false`), `Service`, edge-TLS `Route`
- [x] 10.2 Create `ConfigMap` (datasource URL, Kafka bootstrap, audit host/port) and `Secret`
      (DB user/password) plus `Secret`-driven `envFrom`
- [x] 10.3 Create PostgreSQL `Deployment`/`Service`/`PersistentVolumeClaim` for dev/test parity with the README
- [x] 10.4 Create Kafka `KafkaTopic` (`orders`) for AMQ Streams and document the Strimzi prerequisite
- [x] 10.5 Add `kustomization.yaml` and an `ImageStream`/`BuildConfig` (or binary build) for OpenShift-native builds
- [x] 10.6 **Deployment**: Use mig-deploy for the application and its dependencies
  - Command: `/skill mig-deploy` for `coolstore-monolith`
  - Dependencies: PostgreSQL, Kafka (`orders` topic), optional Keycloak realm `eap`
  - Network: expose 8080 via Service/Route; egress to postgres:5432 and kafka:9092
- [x] 10.7 **Documentation**: rollout/rollback runbook (`oc apply -k`, `oc rollout undo`, previous WAR/EAP image)

## Task Group 11: Documentation, traceability, and final verification

- [x] 11.1 Rewrite `README.md`: Quarkus build/run, configuration table, API contract, OpenShift deploy, testing
- [x] 11.2 Record the migration decision log (cart store, Kafka vs JMS, static content, audit stream) and residual risks
- [x] 11.3 Verify no `javax.*` enterprise imports, no `weblogic`, no EJB/JNDI, no `@MessageDriven`, no `javax.json` remain
      (`grep` gate that can run without a JDK)
- [x] 11.4 Re-run `rgctl discover` + `rgctl rules run rules` and confirm reduced coupling and 0 Konveyor violations
- [x] 11.5 Produce `mig-execute-workspace/EXECUTION_REPORT.md` and `migiq-workspace/MIGRATION_REPORT.md`
