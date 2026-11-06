# Migration Tasks

> **Source of truth for scope:** `mig-prompt-workspace/migration-prompt.md`
> **Graph evidence:** rgctl v0.4.20 index in `.rgctl/` (regenerate full repo with
> `rgctl discover . --with-cfg --with-security --with-taint --with-dashboard --with-harmonic --export-migration-hints`;
> for planning-grade Java evidence use `rgctl discover . -l java --with-cfg --with-security --with-harmonic --export-migration-hints`)
> **Target:** Quarkus 3.x / Java 21 / Jakarta EE 10 (`jakarta.*`) on Red Hat OpenShift.

## How this plan is ordered

The task groups follow the application's real dependency direction, derived from the rgctl
call graph (138 intra-Java `CALLS` edges, Java-scoped index) and the import graph:

```
model (8 classes, 0 internal deps)  ─┬─> persistence (Resources → EntityManager)
                                     ├─> utils  (Transformers → model)
                                     └─> service (→ model, utils; ShoppingCartService → ShippingServiceRemote via JNDI)
                                              └─> rest (CartEndpoint → ShoppingCartService; ProductEndpoint → ProductService; OrderEndpoint → OrderService)
```

**Index used for this plan** (recorded so downstream stages do not re-derive it):

```bash
rgctl discover . -l java --with-cfg --with-security --with-harmonic \
  --export-migration-hints --migration-preset hybrid_default --migration-order scheduled
rgctl -f json metrics --pagerank      # 680 nodes / 1,960 edges, 164 functions
rgctl -f json communities list        # 539 communities, modularity 0.36
rgctl -f json relations --edge calls --limit 2000   # 138 intra-Java CALLS edges
rgctl -f json rules run rules         # 5 rules, 1 violation
```

Consequences baked into the ordering below:

- `ShoppingCart` is the hub type (`getShoppingCartItemList` 7 incoming calls,
  `getCartItemTotal` 4), `ShoppingCartService.getShoppingCart` 5, `CartEndpoint.add` 5,
  `ShoppingCartService.priceShoppingCart` 4 — so the **domain model and pricing service must
  be stable before REST work starts**.
- Java 21 is **not** deferrable: `audit-logging-library` 2.0.0 uses a `record` `AuditEvent`
  and virtual threads, so the toolchain and the audit dependency move together (Group 2/4).
- There are **no tests** in the repository (`src/test` absent, `maven.test.skip=true`), so
  characterization must be completed before any behavioural change (Group 1).
- `migration_plan.json` from the **Java-scoped** index contains 7 steps and is usable as a
  scheduling hint:

  | rgctl step | Community | Priority score | Plan placement |
  |---|---|---|---|
  | 1 | `weblogic.i18n.logging` | 0.16 | Group 2.5 (delete vendor stubs) |
  | 2 | `weblogic.application` | −0.17 | Group 2.5 (delete vendor stubs) |
  | 3 | `com.redhat.coolstore.persistence` | −0.17 | Group 3 |
  | 4 | `com.redhat.coolstore.rest` | 0.19 (highest priority) | Group 6 (last code group) |
  | 5 | `com.redhat.coolstore.utils` | 0.14 | Group 4 |
  | 6 | `com.redhat.coolstore.service` | 0.06 | Group 5 |
  | 7 | `com.redhat.coolstore.model` | −0.13 | Group 3 |

  The roadmap is **priority-ranked, not build-ordered** (it puts `rest` first on score while
  the call graph puts it last). This plan follows dependency order instead and uses the
  roadmap only for "vendor stubs first" and for test-prioritisation of hotspots.
- A full-repository index (`rgctl discover .` without `-l java`) exports 155 steps and
  ~23,900 communities; only **7 of the 155 steps are Java packages**, and they are diluted
  across the schedule (`model` at step 15, `utils` 16, `service` 17, `rest` 18,
  `weblogic.application` 31, `weblogic.i18n.logging` 39, `persistence` 47). The vendored
  `bower_components` JavaScript dominates the ranking, so the full-repo roadmap **must not be
  used to order the Java work** — use the Java-scoped roadmap above.
- `rgctl rules run rules` reports exactly one violation today (`audit-logging-0003`,
  `OrderService.java:5`), because the dependency rule (`audit-logging-0001`) is not matched by
  the current index and the logger is constructed but never used to emit an event. Group 4
  must therefore verify the pom change and the (currently missing) event emission manually.
- Community detection over the Java index returned 539 communities (modularity 0.36,
  over-fragmented at method level), so communities are **not** usable as subsystem
  boundaries; Java packages are the unit of work.

Legend: each group ends with the mandatory integration hooks — **Test Generation**,
**Containerization**, **Deployment**, **Documentation**.

---

## Task Group 1: Baseline, Characterization Tests and Build Guardrails

Goal: a reproducible baseline of current JBoss EAP behaviour and performance, plus a build that
actually runs tests. Nothing in this group changes application behaviour.

- [x] 1.1 Force a reproducible baseline build
  - Record the exact toolchain: `mvn -version`, JDK 17 per README, Maven 3.8.x.
  - Run `mvn -B clean package`; confirm `target/ROOT.war` and its checksum are captured.
  - Reconcile the three conflicting Java levels: `pom.xml` (`source`/`target` `1.8`),
    README (OpenJDK 17), `.github/workflows/main_clustertesting(dev).yml` (Java 11).
    Decide and document one baseline JDK; add `<maven.compiler.release>` in a later group.
  - Record the hand-applied JBoss prerequisites as a script: `CoolstoreDS` datasource
    (`jdbc:postgresql://127.0.0.1:5432/postgresDB`), `jms-topic add --topic-address=topic.orders
    --entries=topic/orders`, `cluster-password`, Keycloak realm `eap` import.
- [x] 1.2 Create `src/test/java` and enable test execution
  - Remove `<maven.test.skip>true</maven.test.skip>`; add JUnit 5, REST Assured and a
    surefire/failsafe configuration that can run against a deployed `ROOT.war` baseline.
  - Add a `baseline` Maven profile (`-Pbaseline`) that runs characterization tests against
    `http://127.0.0.1:8080` instead of the embedded runtime.
- [x] 1.3 Characterization tests — catalogue and product API
  - `ProductEndpoint` (`/services/products`, `/services/products/{itemId}`) and
    `CatalogService.getCatalogItems/getCatalogItemById/updateInventoryItems`.
  - `Transformers.toProduct` mapping including the `inventory == null` branch that logs a
    warning and leaves `location`/`link`/`quantity` unset.
  - Assert exact JSON field names (`itemId`, `name`, `desc`, `price`, `location`, `link`,
    `quantity`) and `Content-Type: application/json`.
- [x] 1.4 Characterization tests — cart and pricing
  - `CartEndpoint`: `GET /services/cart/{cartId}`, `POST /services/cart/{cartId}/{itemId}/{quantity}`,
    `POST /services/cart/{cartId}/{tmpId}` (cart merge), `DELETE /services/cart/{cartId}/{itemId}/{quantity}`,
    `POST /services/cart/checkout/{cartId}`.
  - `ShoppingCartService.priceShoppingCart` ordering: `initShoppingCartForPricing` →
    `PromoService.applyCartItemPromotions` → totals → shipping → `applyShippingPromotions`.
  - `ShippingService` tier table (2.99 / 4.99 / 6.99 / 8.99 / 10.99) and
    `calculateShippingInsurance` (2 % / 1.5 % / 1 %, `BigDecimal.HALF_UP` scale 2), including
    the `cartItemTotal >= 25` gate.
  - `CartEndpoint.dedupeCartItems` merge-by-itemId behaviour.
- [x] 1.5 Characterization tests — checkout, messaging and inventory
  - Capture the exact message body produced by `Transformers.shoppingCartToJson`
    (`orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`,
    `shippingDiscount`, `items[].productSku`, `items[].quantity`) — this is the wire contract.
  - `OrderServiceMDB.onMessage` → `OrderService.save` → `CatalogService.updateInventoryItems`
    decrement, and the `LOW_THRESHOLD = 50` low-stock branch of `InventoryNotificationMDB`
    (document that this class is never activated on JBoss — see 5.6).
  - `OrderEndpoint` (`/services/orders`, `/services/orders/{orderId}`) including the
    `long` path-param conversion.
- [ ] 1.6 Capture the performance baseline
  - Measure p95 latency and throughput for `/services/products` and the checkout flow against
    the 2-node JBoss EAP deployment; record JVM heap, pod/node memory footprint, startup time
    and WAR size. These are the comparison targets for Group 9.
- [x] 1.7 Add build guardrails (failing CI is better than a silent regression)
  - CI job matrix on the chosen baseline JDK; block on test failures.
  - Prepare (do not yet enforce) a `javax.*` EE-import gate:
    `grep -rE "^import javax\.(ejb|persistence|ws|rs|inject|enterprise|annotation|jms|json|xml\.bind|naming|rmi|sql|transaction)" src/main/java`
    must return zero matches from Group 2 onward.
- [x] 1.8 **Test Generation**: Use mig-test-gen to formalize the characterization suite
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.rest` + `com.redhat.coolstore.service`
  - Expected: JUnit 5 + REST Assured suites for the 9 JAX-RS endpoint methods and the pricing
    rules; golden-file tests for the checkout JSON payload; Flyway from-empty and
    already-migrated cases.
- [ ] 1.9 **Containerization**: Produce a frozen reference image of the current application
  - Command: `/skill mig-containerize` for the JBoss EAP 7.4 baseline (reference only, not for
    production) so before/after response and performance diffs can be run side by side.
  - Config needs: `CoolstoreDS` URL/user/password as build-time-injected env vars, Keycloak
    URL `http://localhost:8081/`, ActiveMQ topic `topic/orders`.
- [ ] 1.10 **Deployment**: Stand up the baseline reference environment
  - Command: `/skill mig-deploy` for the baseline stack (JBoss EAP 7.4 + PostgreSQL +
    Keycloak 20) as a throwaway namespace.
  - Dependencies: PostgreSQL 5432, Keycloak 8081, broker topic `topic/orders`.
  - Network: expose 8080 for the SPA and `/services`; keep it isolated from production.
- [x] 1.11 **Documentation**: Record the baseline
  - `docs/baseline.md`: toolchain, WAR checksum, endpoint inventory, pricing tables, message
    contract, performance numbers, and the exact JBoss CLI setup commands from the README.

---

## Task Group 2: Build and Platform Foundation (Java 21, Quarkus BOM, Jakarta namespace)

Goal: the project compiles as a Quarkus 3 application on Java 21 with `jakarta.*` imports and
no vendor-specific code. Behaviour must still be identical.

- [x] 2.1 Rewrite `pom.xml` for Quarkus 3
  - Add `io.quarkus.platform:quarkus-bom` (latest stable 3.x LTS) in `dependencyManagement`.
  - Add `quarkus-maven-plugin` with `build`, `generate-code`, `generate-code-tests` goals;
    set `quarkus.package.jar.type=fast-jar`.
  - Set `<maven.compiler.release>21</maven.compiler.release>` and `maven.compiler.parameters=true`.
  - Change `<packaging>war</packaging>` → `jar`; delete `maven-war-plugin`, `finalName ROOT`,
    and the empty `<profiles><!-- TODO: Add OpenShift profile here --></profiles>` placeholder
    (replaced by 2.6 profiles).
  - Remove `javax:javaee-web-api:7.0`, `javax:javaee-api:7.0`,
    `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec:2.0.0.Final`,
    `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec:1.0.2.Final`.
  - Add extensions: `quarkus-rest-jackson` (or `quarkus-resteasy-reactive-jackson`),
    `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-flyway`,
    `quarkus-arc`, `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`,
    `quarkus-messaging` (Group 5), `quarkus-oidc` (Group 6).
- [x] 2.2 Jakarta namespace sweep across `src/main/java`
  - Exact substitutions found in the codebase:
    `javax.ejb.*` → `jakarta.ejb.*` (Stateless 5, Stateful 1, Singleton 1, Startup 1, Remote 1,
    MessageDriven 1, ActivationConfigProperty, TransactionManagement/Type),
    `javax.persistence.*` → `jakarta.persistence.*` (Entity 4, Table 4, Id 3, Column 3,
    EntityManager 3, GeneratedValue 2, Criteria* 6, OneToMany, JoinColumn, FetchType,
    CascadeType, UniqueConstraint, PersistenceContext),
    `javax.ws.rs.*` → `jakarta.ws.rs.*` (Path 3, GET 2, POST, DELETE, Produces 2, Consumes,
    PathParam 2, ApplicationPath, MediaType, core.Application),
    `javax.inject.Inject` → `jakarta.inject.Inject` (12 occurrences),
    `javax.enterprise.*` → `jakarta.enterprise.*` (Produces 2, RequestScoped 2, Dependent 2,
    SessionScoped, ApplicationScoped, InjectionPoint),
    `javax.annotation.*` → `jakarta.annotation.*` (Resource 2, PostConstruct 2, PreDestroy),
    `javax.jms.*` → `jakarta.jms.*` (Topic, TextMessage, MessageListener, Message,
    JMSException, JMSContext),
    `javax.json.*` → `jakarta.json.*` (temporary; replaced by Jackson in 4.1),
    `javax.xml.bind.annotation.XmlRootElement` → Jackson (2.4),
    `javax.naming.*`/`javax.rmi.*`/`javax.sql.DataSource` → reworked in 3.3/5.2.
  - Verify with the 1.7 grep gate; the build must succeed with **no** `javax.*` EE artifact on
    the classpath.
- [x] 2.3 Introduce Quarkus configuration
  - Create `src/main/resources/application.properties` with `quarkus.application.name=coolstore`,
    HTTP port 8080, `quarkus.http.root-path=/` and the `/services` REST prefix preserved.
  - Add a `%dev` profile (Dev Services for PostgreSQL, permissive OIDC) and a `%prod` profile
    driven only by environment variables.
  - Delete `src/main/webapp/WEB-INF/web.xml` and `beans.xml`; `persistence.xml` is replaced in
    Group 3 (keep it until 3.3 so the baseline comparison remains explainable).
- [x] 2.4 Replace JAXB annotation with Jackson
  - `InventoryEntity` is annotated `@XmlRootElement` (`javax.xml.bind.annotation`); remove the
    JAXB import/annotation and rely on Jackson (Quarkus REST defaults to Jackson), keeping the
    serialized field names identical.
  - `Order` (the REST response type for `/services/orders`) has no JAXB annotation — verify
    its Jackson serialization matches the current JSON field names used by the SPA.
- [x] 2.5 Delete vendor-specific dead code
  - Delete `src/main/java/weblogic/application/ApplicationLifecycleEvent.java`,
    `.../ApplicationLifecycleListener.java`, `src/main/java/weblogic/i18n/logging/NonCatalogLogger.java`.
  - Delete `com.redhat.coolstore.utils.StartupListener` (extends the WebLogic listener and is
    never registered anywhere). Record the decision in `docs/decisions/`.
  - Delete `src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` **or**
    schedule its rewrite in 5.6 — it targets `t3://localhost:7001` and
    `weblogic.jndi.WLInitialContextFactory` and cannot work on any target.
- [x] 2.6 Add the OpenShift-oriented build profiles
  - `%prod` runtime profile plus `quarkus.container-image.*` settings (group, name, tag,
    registry) so `mvn package -Dquarkus.container-image.build=true` works in Group 7.
  - Keep a `native` profile placeholder (optional, not on the critical path).
- [x] 2.7 Set up the Quarkus test harness
  - Add `quarkus-junit5`, `rest-assured`, `quarkus-test-security-oidc`; convert the Group 1
    characterization tests so the same assertions run under `@QuarkusTest`.
- [x] 2.8 **Test Generation**: Use mig-test-gen to lock down the build migration
  - Command: `/skill mig-test-gen` for the namespace/toolchain change
  - Expected: a compile-gate test/CI check that fails on any `javax.*` EE import, plus smoke
    tests booting the Quarkus application context (`@QuarkusTest` with a stub datasource).
- [ ] 2.9 **Containerization**: Validate the Quarkus build produces a runnable artifact
  - Command: `/skill mig-containerize` for the Quarkus fast-jar (skeleton image only)
  - Config needs: `QUARKUS_DATASOURCE_*`, `QUARKUS_OIDC_*`, `QUARKUS_MESSAGING_*` env vars.
- [ ] 2.10 **Deployment**: Dry-run the Quarkus artifact in a dev namespace
  - Command: `/skill mig-deploy` for the new build (no production traffic)
  - Dependencies: PostgreSQL only; broker/OIDC still stubbed.
  - Network: expose 8080, confirm `/q/health/live` responds.
- [x] 2.11 **Documentation**: Document the toolchain decision
  - Update README build section: Java 21, `mvn quarkus:dev`, packaging change from WAR to jar,
    and a `MIGRATION.md` note explaining why `war`/`ROOT.war` deployment is gone.

---

## Task Group 3: Domain Model and Persistence (Jakarta Persistence 3.1 + Flyway 10)

Goal: entities and the datasource work on Quarkus against the unchanged PostgreSQL schema.

- [x] 3.1 Migrate the 8 model classes
  - `model/CatalogItemEntity.java`, `InventoryEntity.java`, `Order.java`, `OrderItem.java`:
    `jakarta.persistence` annotations, verify `@Table`/`@Column` names still match
    `PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`.
  - `Order`/`OrderItem` `@OneToMany(cascade=..., fetch=...)` + `@JoinColumn` FK
    (`FK2BFF474F65938E8`, `ORDER_ID`) must map to the existing DDL — do not let Hibernate
    generate schema.
  - `Product`, `Promotion`, `ShoppingCart`, `ShoppingCartItem` are plain DTOs — confirm no
    hidden JPA dependency and keep `Serializable`/`serialVersionUID` values.
  - Keep the mixed-case column names (`itemId`, `orderId`, `productId`) exactly as in
    `V1_1__CreateSchema.sql`; add `@Column(name = "...")` where Hibernate naming would drift.
- [x] 3.2 Remove the CDI `EntityManager` producer
  - Delete `com.redhat.coolstore.persistence.Resources` (`@Dependent` producer) — Quarkus
    provides `EntityManager`/`Session` injection. Update `CatalogService`, `ProductService`,
    `OrderService` to keep `@Inject EntityManager em` (unchanged call sites) or switch to
    Panache only if it does not change behaviour.
- [x] 3.3 Configure the datasource in `application.properties`
  - `quarkus.datasource.db-kind=postgresql`,
    `quarkus.datasource.jdbc.url=${DB_URL:jdbc:postgresql://localhost:5432/postgresDB}`,
    username/password from `DB_USER`/`DB_PASSWORD`.
  - `quarkus.datasource.jdbc.max-size`, `quarkus.hibernate-orm.database.generation=none`,
    `quarkus.hibernate-orm.log.sql=false` (preserves the current `show_sql=false`).
  - Delete `src/main/resources/META-INF/persistence.xml` **after** confirming the same
    persistence unit name (`primary`) is not referenced anywhere else.
  - Blocking JDBC: keep the endpoints blocking (do not add `@Blocking` on REST resources that
    already run on the worker thread of `quarkus-rest`; verify thread model explicitly).
- [x] 3.4 Replace the Flyway startup bean with the Quarkus extension
  - Delete `com.redhat.coolstore.utils.DataBaseMigrationStartup` (`@Singleton @Startup`,
    `new Flyway()`, `setDataSource`, `baseline()`, `migrate()` — the 4.x API is removed in 9/10).
  - Add `quarkus-flyway`; set `quarkus.flyway.migrate-at-start=true`,
    `quarkus.flyway.baseline-on-migrate=true` (only for pre-existing databases),
    `quarkus.flyway.locations=db/migration`.
  - Keep `V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql` **byte-identical** — Flyway
    checksums in `flyway_schema_history` must not change.
- [x] 3.5 Validate the schema contract end to end
  - Empty database → Flyway V1_1 + V1_2 apply; 9 `PRODUCT_CATALOG` / 9 `INVENTORY` rows present.
  - Pre-migrated database snapshot → application starts without checksum errors or re-runs.
  - Hibernate validates mappings (`quarkus.hibernate-orm.validate-in-dev-mode` or a
    `schema-validation` test) against the Flyway-created tables.
- [x] 3.6 **Test Generation**: Use mig-test-gen for the persistence layer
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.model` + `com.redhat.coolstore.persistence`
  - Expected: entity mapping tests, `@QuarkusTest` repository tests with Dev Services
    PostgreSQL, Flyway from-empty/from-migrated tests, and a `Product`/`Order` round-trip test.
- [x] 3.7 **Containerization**: Package with the real datasource wiring
  - Command: `/skill mig-containerize` for the persistence-enabled build
  - Config needs: `DB_URL`, `DB_USER`, `DB_PASSWORD` (Secret), `QUARKUS_FLYWAY_MIGRATE_AT_START`.
- [ ] 3.8 **Deployment**: Apply against a real PostgreSQL instance
  - Command: `/skill mig-deploy` for app + PostgreSQL
  - Dependencies: PostgreSQL 5432 with the `postgresDB` database.
  - Network: app → `postgres:5432`; no inbound DB exposure.
- [x] 3.9 **Documentation**: Document data layer changes
  - `docs/data.md`: datasource env vars, Flyway behaviour and checksum preservation rules,
    schema/entity mapping table, and the removal of `CoolstoreDS` JNDI.

---

## Task Group 4: Utilities and Audit Logging Library 2.0.0

Goal: cross-cutting utilities work on Java 21 and the audit library is a normal dependency with
the new async/TCP API.

- [x] 4.1 Rewrite `Transformers` JSON handling
  - `utils/Transformers.java` currently uses JSON-P (`Json.createArrayBuilder`,
    `createObjectBuilder`, `JsonReader`, `JsonWriter`, `StringReader`, `StringWriter`).
  - Replace with Jackson `ObjectMapper`/`ObjectNode`/`ArrayNode` (or keep JSON-P via
    `quarkus-jsonp` + `jakarta.json` if you prefer a minimal diff — pick one and document).
  - **Preserve the wire format exactly**: `orderValue`, `customerName`, `customerEmail`,
    `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items[].productSku`,
    `items[].quantity`; keep the `RANDOM_NAMES`/`RANDOM_EMAILS` randomisation behaviour and
    `ThreadLocalRandom` selection so characterization tests still pass.
  - `jsonToOrder` must tolerate the same malformed input as before (currently wraps
    `JsonParsingException` in `RuntimeException`).
- [x] 4.2 Replace the `Logger` CDI producer
  - `utils/Producers.java` (`@Produces Logger produceLog(InjectionPoint)`) →
    `org.jboss.logging.Logger` injection (Quarkus-native) or keep `java.util.logging.Logger`
    with `quarkus-logging-jboss-logmanager`. Update the 5 `@Inject Logger` sites
    (`CatalogService`, `ShoppingCartService`, `ShoppingCartOrderProcessor`, plus
    `DataBaseMigrationStartup` and `StartupListener`, both deleted in Groups 3/2.5).
    `Transformers` uses a `static` `Logger.getLogger(...)` (not CDI) and needs no change.
- [x] 4.3 Consume `audit-logging-library` 2.0.0 as a normal dependency
  - Replace the `system`-scope dependency + `systemPath ${project.basedir}/lib/...1.0.0.jar`
    with a regular `compile` dependency on `com.enterprise:audit-logging-library:2.0.0`.
  - Provide resolution for container builds: `maven-install-plugin` execution in the build
    stage, or an internal Maven repository. `lib/audit-logging-library-1.0.0.jar` must no
    longer be referenced; keep `lib/audit-logging-library-2.0.0.jar` only as the source of the
    install step until it is published.
- [x] 4.4 Rewrite the audit call site in `OrderService`
  - Current code (`OrderService.java`, violation `audit-logging-0003` at line 5):
    ```java
    AuditConfiguration config = new AuditConfiguration();
    config.setLogDirectory("./device-inventory-audit-logs");
    config.setAutoCreateDirectory(true);
    auditLogger = new FileSystemAuditLogger(config);
    ```
  - Target API (verified against the 2.0.0 jar):
    - `FileSystemAuditLogger` is **removed** → use
      `new StreamableAuditLogger(AuditConfiguration)`; it serialises JSON and writes to a TCP
      socket (`streamHost`, `streamPort`, default `localhost:9000`), asynchronously on virtual
      threads.
    - `AuditConfiguration` gains `streamHost`, `streamPort`, `streamProtocol`,
      `streamBufferSize`, `streamReconnectMs` (+ `audit.stream.*` property keys). Bind them
      from MicroProfile Config / env vars, not hardcoded paths.
    - `AuditEvent` is now a **record** with 14 components in order:
      `(Instant timestamp, String eventType, String userId, String sessionId, String application,
      String component, String action, String resource, AuditResult result, String message,
      Map<String,Object> details, String correlationId, String sourceIp, String userAgent)`.
      `AuditEvent.builder()` no longer exists (`audit-logging-0002`); construct the record
      directly.
    - `AuditLogger.logEvent(AuditEvent)` is `@Deprecated`; use
      `logEventAsync(AuditEvent)` (returns `CompletableFuture<Void>`) or
      `logEventAsyncSafe(...)` for fire-and-forget. `logSuccess`/`logFailure`/`logDenied` are
      gone (`audit-logging-0005`) — build the record with `AuditResult` instead.
  - **Decide the emission policy**: today the logger is only constructed (`init`) and closed
    (`cleanup`) — no `AuditEvent` is ever emitted, so the audit trail is effectively empty.
    Either (a) emit `logEventAsync` from `OrderService.save` (order created) with
    `action`/`resource`/`result` populated — preferred, and covered by a characterization test
    in Group 1 — or (b) delete the logger entirely and record the decision. Do not port the
    dead initialization as-is.
  - Re-run `rgctl rules run rules` and require **zero** violations for
    `audit-logging-0001..0005`; additionally assert the pom no longer references 1.0.0 and
    that no `com.enterprise.audit.logging.model.AuditEvent$Builder` bytecode reference remains
    (`rg -n 'AuditEvent\.builder|FileSystemAuditLogger|logEvent\(' src/main/java`).
- [x] 4.5 Handle the audit transport in every environment
  - `audit.stream.host`/`port` from `AUDIT_STREAM_HOST`/`AUDIT_STREAM_PORT`.
  - Provide a dev/test profile that either points at a local collector or uses a no-op
    implementation, so `@QuarkusTest` does not attempt TCP connections
    (`StreamableAuditLogger.runAsync` throws `Failed to send audit event over TCP` on failure).
  - Decide whether the audit logger is `@ApplicationScoped` with `@PreDestroy close()`
    (current shape) — keep the existing lifecycle semantics.
- [x] 4.6 **Test Generation**: Use mig-test-gen for utilities and audit
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.utils` + audit integration
  - Expected: golden-file tests for `shoppingCartToJson`/`jsonToOrder`, `AuditEvent` record
    construction tests, and a fake-TCP-server test asserting NDJSON is streamed and
    `logEventAsync` completes (or fails safely).
- [x] 4.7 **Containerization**: Verify the audit jar is resolvable inside the image build
  - Command: `/skill mig-containerize` for the audit-enabled build
  - Config needs: `AUDIT_STREAM_HOST`, `AUDIT_STREAM_PORT` (ConfigMap); confirm no `lib/*.jar`
    `systemPath` dependency survives in the image build.
- [ ] 4.8 **Deployment**: Deploy with audit streaming configured
  - Command: `/skill mig-deploy` for app + audit collector endpoint
  - Dependencies: TCP collector at `audit.stream.host:port` (may be an external service).
  - Network: egress TCP to the collector; document firewall/NetworkPolicy requirements.
- [x] 4.9 **Documentation**: Document the audit upgrade
  - `docs/audit.md`: 1.0.0 → 2.0.0 API mapping table (builder → record, `FileSystemAuditLogger`
    → `StreamableAuditLogger`, `logEvent` → `logEventAsync`, config keys), how to run a local
    collector, and the rule IDs that were satisfied.

---

## Task Group 5: Business Services (EJB → CDI, remote lookup removal, cart state, messaging)

Goal: replace the EJB/JMS programming model with CDI + SmallRye Reactive Messaging while
keeping the pricing, checkout and inventory behaviour identical.

- [x] 5.1 Convert stateless EJBs to CDI beans
  - `CatalogService`, `ProductService`, `OrderService`, `PromoService`,
    `ShoppingCartOrderProcessor`: `@Stateless` → `@ApplicationScoped`; add
    `jakarta.transaction.Transactional` only where a transaction boundary is actually needed
    (previously supplied by the EJB container).
  - Remove `@TransactionManagement(BEAN)` semantics that came with `DataBaseMigrationStartup`
    (already deleted in 3.4).
  - Keep method signatures unchanged so the 129-edge call graph and the REST call sites stay
    valid.
- [x] 5.2 Remove the remote EJB lookup for `ShippingService`
  - Delete `service/ShippingServiceRemote.java` and the `@Remote` annotation on
    `ShippingService`; drop `javax.rmi.PortableRemoteObject` usage.
  - In `ShoppingCartService`, replace `lookupShippingServiceRemote()` (WildFly
    `InitialContext`, `ejb:/ROOT/ShippingService!com.redhat.coolstore.service.ShippingServiceRemote`)
    with `@Inject ShippingService` — this removes a NamingException path and one JNDI
    round-trip on **every** `priceShoppingCart` call (called twice per pricing pass:
    shipping + insurance).
- [x] 5.3 Re-model the stateful cart
  - `ShoppingCartService` is `@Stateful` and holds `private ShoppingCart cart = new ShoppingCart()`
    as instance state; `CartEndpoint` is `@SessionScoped`. Neither survives a Quarkus
    restart or a 2-replica rollout.
  - Choose and implement one: (a) keep the cart map in an `@ApplicationScoped` bean keyed by
    `cartId` with an eviction policy, (b) session-scoped bean backed by Infinispan/Redis, or
    (c) return the cart to the client and accept it back on each call. Document the choice and
    its consistency semantics in `docs/cart-state.md`.
  - Preserve the `cartId`/`tmpId` merge semantics used by `POST /services/cart/{cartId}/{tmpId}`
    and the SPA's `localStorage` cart transfer (`app/services/cart.js`).
- [x] 5.4 Replace the JMS producer with reactive messaging
  - `ShoppingCartOrderProcessor`: `@Inject JMSContext`, `@Resource(lookup="java:/topic/orders") Topic`
    → SmallRye `Emitter<String>` on channel `orders` (`@Channel("orders")`), sending the same
    JSON string from `Transformers.shoppingCartToJson`.
  - Configure `mp.messaging.outgoing.orders.*` (connector, topic/address, serializer).
- [x] 5.5 Replace the message-driven bean with a reactive consumer
  - `OrderServiceMDB` (`@MessageDriven`, `destinationLookup=topic/orders`,
    `destinationType=javax.jms.Topic`, `acknowledgeMode=Auto-acknowledge`) →
    `@Incoming("orders")` consumer method that keeps the exact logic: parse with
    `Transformers.jsonToOrder`, `orderService.save(order)`, then for each
    `orderItem` call `catalogService.updateInventoryItems(productId, quantity)`.
  - Preserve at-least-once semantics; make the consumer idempotent or document the duplicate
    risk (the current code would double-decrement on redelivery).
  - Configure `mp.messaging.incoming.orders.*` with a stable consumer group so replicas share
    the topic (equivalent to the JBoss EAP 2-node competing-consumer demo in the README).
- [x] 5.6 Decide on `InventoryNotificationMDB`
  - It is not annotated and never activated; it contains a WebLogic JNDI client
    (`t3://localhost:7001`), `PortableRemoteObject.narrow`, `TopicConnection`/`TopicSubscriber`.
  - Either delete it (default recommendation, covered by 2.5) or reimplement its low-stock
    (`LOW_THRESHOLD = 50`) notification as a second `@Incoming` consumer with a distinct
    channel. Record the decision; do not port the WebLogic code.
- [x] 5.7 Configure messaging for dev and prod
  - `%dev`: in-memory/Kafka Dev Services (`quarkus.messaging.*`) so tests do not need a broker.
  - `%prod`: broker bootstrap from `KAFKA_BOOTSTRAP_SERVERS` or AMQP host, channel `orders`,
    topic/address `orders` (mapping from the old `topic/orders`), consumer group
    `coolstore-order-service`.
- [x] 5.8 **Test Generation**: Use mig-test-gen for services and messaging
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.service`
  - Expected: pricing/promotion unit tests (PromoService, ShippingService tiers),
    `@QuarkusTest` cart-state tests across two simulated replicas, emitter/consumer tests with
    an in-memory channel, and an idempotency test for duplicate order delivery.
- [x] 5.9 **Containerization**: Containerize the messaging-enabled service
  - Command: `/skill mig-containerize` for the app with messaging
  - Config needs: `KAFKA_BOOTSTRAP_SERVERS` (ConfigMap), consumer group, audit stream vars.
- [ ] 5.10 **Deployment**: Deploy with 2 replicas and a broker
  - Command: `/skill mig-deploy` for app (replicas: 2) + broker
  - Dependencies: PostgreSQL, broker with the `orders` topic, Keycloak.
  - Network: app → broker (9092 or 5672), app → PostgreSQL, app → Keycloak.
- [x] 5.11 **Documentation**: Document the new service model
  - `docs/services.md`: EJB → CDI mapping table, why the remote `ShippingService` lookup was
    removed, cart-state design and its failure semantics, JMS → reactive messaging channel
    contract, and the competing-consumer configuration.

---

## Task Group 6: REST Layer, SPA Hosting and OIDC

Goal: identical REST surface under `/services`, the AngularJS SPA served as static assets, and
Keycloak SSO working without the legacy adapter.

- [x] 6.1 Fix the JAX-RS resource scopes
  - `CartEndpoint` is `@SessionScoped` + `@Path("/cart")` + `Serializable`. HTTP-session-scoped
    JAX-RS resources are not supported by Quarkus REST; make it `@ApplicationScoped` (or
    `@RequestScoped`) and move cart state into the store chosen in 5.3.
  - `ProductEndpoint`/`OrderEndpoint` keep `@RequestScoped` → `@ApplicationScoped` where they
    are stateless; do not change the `@Path` values (`/products`, `/orders`).
  - `RestApplication` keeps `@ApplicationPath("/services")`; verify the SPA's hardcoded
    `/services/cart`, `/services/products` base URLs (`app/services/cart.js`,
    `app/services/catalog.js`) still resolve.
- [x] 6.2 Verify the 9 endpoint methods behave identically
  - `CartEndpoint`: `getCart`, `checkout`, `add`, `set`, `delete`, `dedupeCartItems`.
  - `ProductEndpoint`: `listAll`, `getProduct`; `OrderEndpoint`: `listAll`, `getOrder`.
  - `@Produces`/`@Consumes(MediaType.APPLICATION_JSON)` preserved; `throws Exception` handling
    for `add`/`set`/`delete` must still surface as 500 unless a deliberate 4xx mapping is
    introduced (if you introduce one, that is a behaviour change — get sign-off).
- [x] 6.3 Move the SPA into Quarkus static resources
  - Move `src/main/webapp/{app,partials,bower_components,index.jsp,health.jsp,coolstore.json,keycloak.json}`
    to `src/main/resources/META-INF/resources/` (excluding `WEB-INF`).
  - Replace `index.jsp` (it only calls `request.getSession(true)`) with a static `index.html`;
    ensure SSO bootstrapping no longer depends on server-side session creation.
  - Replace `health.jsp` with SmallRye Health (`/q/health`, `/q/health/live`, `/q/health/ready`).
  - Decide whether `bower_components` is kept as-is (fastest, largest image) or pruned to the
    libraries actually referenced by `index.jsp`; document image-size impact.
- [x] 6.4 Replace the Keycloak legacy adapter configuration
  - `src/main/webapp/keycloak.json` hardcodes `auth-server-url: http://localhost:8081/`,
    `realm: eap`, `resource: eap-app`, `public-client: true`.
  - Serve runtime OIDC config from ConfigMap/env (e.g. generate `keycloak.json`-shaped JSON at
    startup or inject `OIDC_AUTH_SERVER_URL`/`OIDC_REALM`/`OIDC_CLIENT_ID`); remove the
    `localhost` literal from the repository.
  - Backend enforcement: add `quarkus-oidc` with `quarkus.oidc.auth-server-url`,
    `quarkus.oidc.client-id`; decide which endpoints are protected (today only the SPA enforces
    login — `/services/**` is open; making it protected is a behaviour change, get sign-off).
  - Keep the `eap` realm contract; `realm-export.json` stays the reference import and must not
    be edited without coordinating with the identity owner.
- [x] 6.5 Make `coolstore.json` runtime-configurable
  - `MONOLITH: true`, `API_ENDPOINT: ""`, `SECURE_API_ENDPOINT: ""`, `SSO_ENABLED: true` are
    static. Move to a generated config endpoint or env-substituted file so dev/staging/prod can
    differ without a rebuild.
- [x] 6.6 **Test Generation**: Use mig-test-gen for REST and SSO
  - Command: `/skill mig-test-gen` for `com.redhat.coolstore.rest`
  - Expected: REST Assured contract tests for all 9 endpoints (status, media type, JSON shape),
    OpenAPI snapshot if added, OIDC flow tests with `quarkus-test-security-oidc`, and a static
    asset test (`/`, `/app/app.js`, `/partials/*.html`, `/bower_components/...` return 200).
- [x] 6.7 **Containerization**: Containerize the full web application
  - Command: `/skill mig-containerize` for the app serving SPA + REST
  - Config needs: `OIDC_AUTH_SERVER_URL`, `OIDC_REALM`, `OIDC_CLIENT_ID` (ConfigMap),
    `OIDC_CLIENT_SECRET` (Secret if confidential), DB and broker vars.
- [ ] 6.8 **Deployment**: Deploy with a Route and SSO
  - Command: `/skill mig-deploy` for the app + Route + Keycloak client config
  - Dependencies: Keycloak 20+ with realm `eap` and client `eap-app`.
  - Network: Route → 8080; app → Keycloak; browser → Keycloak (redirect URIs must include the
    Route hostname).
- [x] 6.9 **Documentation**: Document the web layer
  - `docs/web.md`: static asset layout, JSP removal and its session implication, OIDC
    configuration matrix (dev/staging/prod), endpoint-to-controller map, and the
    `/services` prefix contract.

---

## Task Group 7: Containerization

Goal: a hardened, reproducible image that runs as non-root and needs no baked-in config.

- [x] 7.1 Write the multi-stage `Containerfile`
  - Build stage: `registry.access.redhat.com/ubi9/openjdk-21` (or `maven:3.9-eclipse-temurin-21`),
    copy `pom.xml` + `lib/` (for the audit jar install) + `src/`, run `mvn -B package`.
  - Runtime stage: `ubi9/openjdk-21-runtime` (or `quay.io/quarkus/quarkus-micro-image`),
    non-root UID (185), copy `target/quarkus-app/`, `ENTRYPOINT ["java","-jar","quarkus-run.jar"]`.
  - Multi-arch build if the cluster is heterogeneous.
- [x] 7.2 Harden and scan the image
  - Fixed UID, read-only root filesystem where possible, no shell, no package manager in the
    runtime layer, `JAVA_OPTS` with container-aware memory (`-XX:MaxRAMPercentage=75`).
  - Run a vulnerability scan (Trivy/ACS) and generate an SBOM; record findings and acceptances.
  - Confirm no `postgresPW`, Keycloak secret, or audit log path is baked into any layer.
- [x] 7.3 Externalize all runtime configuration
  - Define the full env var contract: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OIDC_AUTH_SERVER_URL`,
    `OIDC_REALM`, `OIDC_CLIENT_ID`, `KAFKA_BOOTSTRAP_SERVERS`, `AUDIT_STREAM_HOST`,
    `AUDIT_STREAM_PORT`, `QUARKUS_HTTP_PORT`.
  - Verify the image starts with only env vars (no `application.properties` edits).
- [x] 7.4 **Test Generation**: Use mig-test-gen for image-level tests
  - Command: `/skill mig-test-gen` for the container image
  - Expected: `@QuarkusIntegrationTest` running against the built image, a startup smoke test,
    and a config-injection test that fails when a mandatory env var is missing.
- [x] 7.5 **Containerization**: Produce the production image
  - Command: `/skill mig-containerize` for the full application
  - Config needs: the complete env var contract from 7.3; Secrets for DB password and OIDC
    client secret; ConfigMap for URLs, realm, client id, broker, audit endpoint.
- [ ] 7.6 **Deployment**: Push to the registry and verify pull-through
  - Command: `/skill mig-deploy` for image promotion (dev → stage)
  - Dependencies: OpenShift internal registry, image pull secret on the service account.
  - Network: registry access from the cluster.
- [x] 7.7 **Documentation**: Document the image
  - `docs/container.md`: build commands, env var reference, UID/filesystem constraints,
    image size, scan results and re-scan cadence, and the audit-jar install mechanism.

---

## Task Group 8: OpenShift Deployment

Goal: declarative, zero-downtime deployment of the monolith plus its dependencies.

- [x] 8.1 Author the manifests (Kustomize or Helm)
  - `Deployment` (replicas ≥ 2), `Service` (8080), `Route` (edge/passthrough TLS),
    `ConfigMap` (non-secret env), `Secret` (DB password, OIDC secret),
    `ServiceAccount`, `NetworkPolicy`.
  - Resource requests/limits sized from the Group 1 baseline (JBoss footprint is the ceiling,
    Quarkus should be lower); `HorizontalPodAutoscaler` on CPU/RPS.
- [x] 8.2 Wire health, lifecycle and graceful shutdown
  - `startupProbe` → `/q/health/started`, `readinessProbe` → `/q/health/ready`,
    `livenessProbe` → `/q/health/live`, with initial delays appropriate for Flyway migration.
  - `terminationGracePeriodSeconds` long enough for the messaging consumer to finish in-flight
    orders; `preStop` sleep or Quarkus graceful shutdown (`quarkus.shutdown.timeout`).
  - Rolling update `maxUnavailable: 0`, `maxSurge: 1`.
- [x] 8.3 Provision the dependencies
  - PostgreSQL: in-cluster operator/StatefulSet with PVC, or bind to a managed instance;
    database `postgresDB`, user `postgresUser` (from Secret), Flyway migrates at start.
  - Broker: AMQ Streams (Kafka) or AMQ Broker with the `orders` topic; consumer group matching
    5.7.
  - Keycloak: realm `eap` imported from `realm-export.json`; client `eap-app` with the Route
    hostname in redirect URIs (no `localhost`).
- [x] 8.4 Wire the delivery pipeline (platform-owned)
  - CI/CD is provided by the migration platform as **Tekton pipelines on OpenShift**:
    build → test → image build/push → manifest update (GitOps) or `oc apply`.
  - **Do not create or modify `.github/workflows/`** (the existing
    `main_clustertesting(dev).yml` Azure Web App workflow stays untouched); a pushed workflow
    change is rejected by the platform. Record the existing workflow's Java 11 / Azure Web App
    assumptions as obsolete in `MIGRATION.md` instead.
  - Do not introduce new pipeline secrets in the repository; credentials belong in OpenShift
    `Secret` objects managed by the platform.
- [x] 8.5 Define the cutover and rollback
  - Cutover: deploy Quarkus app alongside the JBoss EAP route, run the E2E suite against the
    new Route, then switch DNS/traffic; keep `ROOT.war` on JBoss deployable for 30 days.
  - Rollback: documented `oc rollout undo`, plus the exact JBoss CLI steps from README
    (`data-source add`, `jms-topic add`, `deploy ./target/ROOT.war`).
  - Note: Flyway schema is shared and compatible, so rollback is code-only (no DB rollback
    needed) — verify no new migration was added.
- [x] 8.6 **Test Generation**: Use mig-test-gen for deployment verification
  - Command: `/skill mig-test-gen` for deployment smoke and failover
  - Expected: post-deploy smoke suite (health, SPA, 9 REST endpoints, checkout),
    replica-failover test for cart state, and a consumer-lag/duplicate-delivery test.
- [ ] 8.7 **Containerization**: Confirm the deployed digest matches the tested image
  - Command: `/skill mig-containerize` for digest verification/re-scan
  - Config needs: none new; validate that the ConfigMap/Secret set is complete and minimal.
- [ ] 8.8 **Deployment**: Full OpenShift environment
  - Command: `/skill mig-deploy` for the complete stack (dev → stage → prod)
  - Dependencies: PostgreSQL, broker, Keycloak, audit collector.
  - Network: Route 443 → 8080; app → PostgreSQL 5432, broker 9092/5672, Keycloak 443,
    audit collector TCP port.
- [x] 8.9 **Documentation**: Operations runbook
  - `docs/openshift.md`: manifests layout, env/secret matrix, probe semantics, scaling,
    backup/restore for PostgreSQL, broker topic management, and the cutover/rollback runbook.

---

## Task Group 9: Verification, Cutover and Handover

Goal: prove behavioural parity, meet the performance target, cut over, and hand the team a
documented, operated system.

- [ ] 9.1 Run the full regression suite against the OpenShift deployment
  - Characterization (Group 1) + unit/integration (Groups 3–6) + integration tests against the
    image (7.4) + E2E: browse → add to cart → apply promotions → checkout → order persisted →
    inventory decremented, including Keycloak login and the cart merge path.
- [ ] 9.2 Compare performance with the Group 1 baseline
  - p95 latency for `/services/products` and checkout: no regression beyond 10 %.
  - Memory (pod RSS) and startup time below the JBoss EAP baseline; record image size.
  - Consumer throughput and lag under 2 replicas with concurrent checkouts.
- [ ] 9.3 Rehearse zero-downtime cutover and rollback
  - Rolling deploy with `maxUnavailable: 0` while a checkout load generator runs; assert zero
    failed checkouts and no duplicate orders.
  - Execute one rollback drill to JBoss EAP and back.
- [x] 9.4 Decommission the old path
  - Remove JBoss-specific artifacts from the repo: `src/main/webapp/WEB-INF/web.xml`,
    `META-INF/persistence.xml`, `weblogic/**`, `lib/audit-logging-library-1.0.0.jar`,
    the Azure workflow, and the `jboss-cli` instructions in README.
  - Decide whether `realm-export.json`, `assets/coolstore.png`, and `.konveyor/` stay.
- [x] 9.5 Finalize documentation and enablement
  - Rewrite README for Quarkus: prerequisites (Java 21, Maven, Podman), `mvn quarkus:dev`,
    test commands, image build, OpenShift deploy, Keycloak realm setup (no `localhost`),
    and a troubleshooting section.
  - Add `MIGRATION.md` (what changed, why, rule IDs addressed, known accepted risks such as
    AngularJS 1.x EOL), `docs/decisions/*`, and a 1-hour enablement session on Quarkus 3, CDI
    scopes, reactive messaging and OpenShift operations.
- [x] 9.6 **Test Generation**: Use mig-test-gen for the final acceptance suite
  - Command: `/skill mig-test-gen` for end-to-end acceptance
  - Expected: committed E2E suite (Playwright/Selenium for the SPA + REST Assured for the API),
    performance test scripts with thresholds, and a CI stage that runs them against stage.
- [x] 9.7 **Containerization**: Freeze the release image
  - Command: `/skill mig-containerize` for the release candidate
  - Config needs: production ConfigMap/Secret references only; no dev defaults.
- [ ] 9.8 **Deployment**: Production cutover
  - Command: `/skill mig-deploy` for production
  - Dependencies: all of the above; change-request approval.
  - Network: production Route, production Keycloak realm, production database/broker.
- [x] 9.9 **Documentation**: Handover package
  - README + `MIGRATION.md` + `docs/{baseline,data,audit,services,web,container,openshift}.md`
    + runbooks + rollback procedure + training recording; verify every doc has an owner.

---

## Dependency summary (execution order)

| Group | Depends on | Blocking reason |
|-------|-----------|-----------------|
| 1 Baseline & characterization | — | No tests exist; behaviour must be pinned first |
| 2 Build & platform foundation | 1 | Namespace/toolchain change needs a green baseline |
| 3 Model & persistence | 2 | Entities cannot compile before `jakarta.*` + Quarkus BOM |
| 4 Utilities & audit 2.0.0 | 2, 3 | `Transformers` feeds both REST and messaging; audit needs Java 21 |
| 5 Business services & messaging | 3, 4 | Pricing/cart/messaging depend on entities and `Transformers` |
| 6 REST & SPA & OIDC | 5 | Endpoints delegate to services; SPA hits `/services` |
| 7 Containerization | 6 | Image needs the full, functional application |
| 8 OpenShift deployment | 7 | Manifests reference the published image |
| 9 Verification & cutover | 8 | Parity can only be proven on the target platform |

Within groups, subtasks are listed in execution order; the `**Test Generation**` hook runs
after the functional subtasks in that group, `**Containerization**`/`**Deployment**` after the
tests pass, and `**Documentation**` closes the group.
