# User Stories

Persistent links point into `mig-plan-workspace/tasks.md` (task groups) and
`mig-prompt-workspace/migration-prompt.md` (scope, constraints, risks).

Story order is the execution order from the dependency table at the end of `tasks.md`.
Estimates assume a small team comfortable with Java EE but new to Quarkus; 1 point ≈ half a day.

| # | Story | Priority | Estimate | Task groups |
|---|-------|----------|----------|-------------|
| 1 | Establish a Behavioural Baseline and Test Safety Net | High | 5 | 1 |
| 2 | Modernize the Build to Quarkus 3 / Java 21 / Jakarta | High | 8 | 2 |
| 3 | Run Persistence on Quarkus with the Unchanged Schema | High | 8 | 3 |
| 4 | Upgrade Utilities and the Audit Library to 2.0.0 | High | 5 | 4 |
| 5 | Move Business Logic to CDI and Reactive Messaging | High | 13 | 5 |
| 6 | Serve the Same REST API and SPA with Keycloak SSO | High | 8 | 6 |
| 7 | Package a Hardened Container Image | Medium | 5 | 7 |
| 8 | Deploy the Application on OpenShift | High | 8 | 8 |
| 9 | Prove Parity, Cut Over and Hand Over | High | 5 | 9 |

---

## User Story 1: Establish a Behavioural Baseline and Test Safety Net

**As a:** QA Engineer
**I want:** characterization tests and a recorded performance baseline for the current JBoss EAP deployment
**So that:** every later migration step can be proven not to change observable behaviour

---

### Tasks
- [Task Group 1: Baseline, Characterization Tests and Build Guardrails](tasks.md#task-group-1-baseline-characterization-tests-and-build-guardrails)

### Acceptance Criteria
- [ ] `mvn -B clean package` produces `target/ROOT.war` reproducibly and its checksum is recorded in `docs/baseline.md`.
- [ ] `mvn test` actually executes tests (the `maven.test.skip=true` flag is removed) and fails the build on failure.
- [ ] Characterization tests exist for all 9 JAX-RS endpoint methods across `CartEndpoint`, `ProductEndpoint`, `OrderEndpoint`.
- [ ] Pricing behaviour is pinned: `ShippingService` tiers 2.99/4.99/6.99/8.99/10.99 and insurance 2 %/1.5 %/1 % with `BigDecimal.HALF_UP` at scale 2.
- [ ] The checkout message contract is pinned as a golden file: `orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items[].productSku`, `items[].quantity`.
- [ ] Flyway behaviour is pinned for both an empty database and an already-migrated database.
- [ ] Baseline p95 latency, throughput, memory footprint, startup time and WAR size are recorded.

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- A JBoss EAP 7.4 instance configured per README (`CoolstoreDS` datasource, `topic/orders` JMS topic, cluster password).
- PostgreSQL 15+ reachable with database `postgresDB`.
- Keycloak 20 with realm `eap` imported from `realm-export.json` and a test user.
- JDK and Maven installed; the conflicting Java versions in `pom.xml` (1.8), README (17) and CI (11) have been reconciled for the baseline.

---

### Steps to Reproduce / Implementation Notes
1. Build and deploy `ROOT.war` to JBoss EAP 7.4 (`deploy ./target/ROOT.war`).
2. Create `src/test/java`, enable test execution, and add a `baseline` Maven profile that targets the deployed instance over HTTP.
3. Write REST Assured contract tests per endpoint; capture responses as golden JSON.
4. Write unit tests for `ShippingService`, `PromoService`, `ShoppingCartService.priceShoppingCart` and `Transformers`.
5. Write an integration test that sends an order to `topic/orders` and asserts `OrderServiceMDB` persists the order and decrements inventory.
6. Record performance numbers with a load generator; store them in `docs/baseline.md`.

---

### Dependencies
- None (entry point of the migration).
- Requires the identity owner to provide the Keycloak realm client (`eap-app`) and a test user.

---

### Test Cases
- Test 1: `GET /services/products` returns the 9 seeded catalogue items with unchanged field names.
- Test 2: `POST /services/cart/{cartId}/329299/2` then `GET /services/cart/{cartId}` returns the priced cart including shipping 2.99.
- Test 3: `POST /services/cart/checkout/{cartId}` publishes the documented JSON to `topic/orders`.
- Test 4: Message consumption persists `ORDERS`/`ORDER_ITEMS` rows and decrements `INVENTORY.quantity`.
- Test 5: Flyway on an already-migrated database performs no re-run and raises no checksum error.

---

### Notes / Comments
- The repository contains no tests at all, so this story is on the critical path and cannot be parallelized with behavioural changes.
- `InventoryNotificationMDB` is not annotated and never activated on JBoss; only its low-stock logic is worth pinning as documentation, not as a running behaviour.
- Keep these tests runnable against both JBoss and Quarkus so they become the parity gate.

---

## User Story 2: Modernize the Build to Quarkus 3 / Java 21 / Jakarta

**As a:** Developer
**I want:** the project built on Quarkus 3 with Java 21 and `jakarta.*` namespaces
**So that:** the application can run on a modern, container-friendly runtime instead of JBoss EAP 7.4

---

### Tasks
- [Task Group 2: Build and Platform Foundation](tasks.md#task-group-2-build-and-platform-foundation-java-21-quarkus-bom-jakarta-namespace)

### Acceptance Criteria
- [ ] `pom.xml` uses the Quarkus BOM and `quarkus-maven-plugin`, `packaging` is `jar`, and `maven.compiler.release` is 21.
- [ ] No `javax.*` EE imports remain in `src/main/java` (enforced by a CI grep gate).
- [ ] No Java EE / JBoss spec artifacts remain on the dependency tree (`mvn dependency:tree` shows none).
- [ ] `weblogic/**` stubs and `StartupListener` are deleted with a recorded decision.
- [ ] `web.xml` and `beans.xml` are removed; `application.properties` carries dev and prod profiles.
- [ ] `mvn quarkus:dev` starts the application and `GET /q/health/live` returns 200.
- [ ] The Group 1 characterization tests still pass unchanged.

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 1 complete (green baseline with characterization tests).
- JDK 21 available in CI and locally.
- Agreement that WAR/`ROOT.war` deployment is being retired.

---

### Steps to Reproduce / Implementation Notes
1. Rewrite `pom.xml` (BOM, plugin, Java 21, jar packaging, drop `javaee-api`/JBoss specs/`maven-war-plugin`).
2. Sweep namespaces (`javax.ejb|persistence|ws.rs|inject|enterprise|annotation|jms` → `jakarta.*`).
3. Add `application.properties`; delete `web.xml` and `beans.xml`.
4. Remove `@XmlRootElement`+JAXB from `Order` in favour of Jackson.
5. Delete `weblogic/**`, `StartupListener`, and decide on `InventoryNotificationMDB`.
6. Enable the `javax.*` grep gate in CI and run the characterization tests under `@QuarkusTest`.

---

### Dependencies
- Story 1 (tests and baseline).
- Blocking for all later stories: nothing compiles against `jakarta.*` until this lands.

---

### Test Cases
- Test 1: full-tree grep for `javax.` EE packages returns zero matches.
- Test 2: `mvn verify` passes on JDK 21 with no `javax.*` artifact present.
- Test 3: `@QuarkusTest` boots the context with a stubbed datasource and `/q/health/live` is 200.
- Test 4: `GET /services/products` returns 200 with the expected payload shape in dev mode.

---

### Notes / Comments
- `Transformers` still uses JSON-P after this story (`jakarta.json`); it is rewritten in Story 4 — do not let it force `quarkus-jsonp` into the final dependency set accidentally.
- The empty `<profiles><!-- TODO: Add OpenShift profile here --></profiles>` block in `pom.xml` is replaced here; the actual OpenShift build settings arrive in Story 7.
- Removing `web.xml` drops the `<distributable/>` flag; clustering semantics are re-established in Story 5/8, not by the servlet spec.

---

## User Story 3: Run Persistence on Quarkus with the Unchanged Schema

**As a:** Developer
**I want:** the JPA entities and Flyway migrations to work on Quarkus against the existing PostgreSQL database
**So that:** existing data and the operational database contract survive the migration untouched

---

### Tasks
- [Task Group 3: Domain Model and Persistence](tasks.md#task-group-3-domain-model-and-persistence-jakarta-persistence-31--flyway-10)

### Acceptance Criteria
- [ ] All 4 JPA entities compile with `jakarta.persistence` and map to the existing tables (`PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`) without schema generation.
- [ ] `quarkus.hibernate-orm.database.generation=none`; Hibernate does not alter the schema.
- [ ] The datasource is configured purely from environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`).
- [ ] `utils/DataBaseMigrationStartup` is deleted and Flyway runs via `quarkus-flyway` with `migrate-at-start=true`.
- [ ] `V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql` are byte-identical to their current content (checksums preserved).
- [ ] `persistence.xml` and the `Resources` `EntityManager` producer are removed without breaking injection in `CatalogService`, `ProductService`, `OrderService`.
- [ ] Flyway applies cleanly on an empty database and is a no-op on a pre-migrated database.

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 2 complete (Quarkus 3 / Java 21 / `jakarta.*`).
- A snapshot of a Flyway-migrated database for the checksum test.
- Confirmation that no other system reads the `CoolstoreDS` JNDI name.

---

### Steps to Reproduce / Implementation Notes
1. Migrate the 8 model classes; add explicit `@Column(name=...)` where naming would drift from the mixed-case DDL.
2. Delete `com.redhat.coolstore.persistence.Resources`; keep `@Inject EntityManager` call sites.
3. Configure `quarkus.datasource.*` and `quarkus.hibernate-orm.*` in `application.properties` (and Dev Services for tests).
4. Replace `DataBaseMigrationStartup` with the `quarkus-flyway` extension; keep migration files untouched.
5. Run mapping/schema validation tests against a Flyway-created database.

---

### Dependencies
- Story 2. Also depends on the DBA/identity of the PostgreSQL instance (credentials and network path).

---

### Test Cases
- Test 1: Empty database → app starts → 4 tables + `flyway_schema_history` exist → 9 products and 9 inventory rows present.
- Test 2: Pre-migrated database → app starts → no migration re-run, no checksum error.
- Test 3: `CatalogItemEntity` round-trip returns `location`, `link`, `quantity` from `INVENTORY`.
- Test 4: `Order` + `OrderItem` persist with the `FK2BFF474F65938E8` foreign key intact.
- Test 5: `CatalogService.updateInventoryItems` decrements `INVENTORY.quantity` exactly as before.

---

### Notes / Comments
- `persistence.xml` sets `hibernate.jdbc.use_get_generated_keys=false` and `show_sql=false`; preserve the observable behaviour (no SQL logging, generated-key handling for `Order.orderId`/`OrderItem.id`).
- Flyway 4.1.2's `baseline()` call is gone; `baseline-on-migrate` is only needed for databases that predate `flyway_schema_history`.
- `float8`/`int4` types and unquoted mixed-case identifiers are PostgreSQL-specific — do not "clean up" the SQL during migration.

---

## User Story 4: Upgrade Utilities and the Audit Library to 2.0.0

**As a:** Developer
**I want:** `Transformers` and the audit logging integration rewritten against their Java 21-era APIs
**So that:** the application serves the same JSON contract and emits audit events asynchronously over TCP

---

### Tasks
- [Task Group 4: Utilities and Audit Logging Library 2.0.0](tasks.md#task-group-4-utilities-and-audit-logging-library-200)

### Acceptance Criteria
- [ ] `Transformers` no longer uses `javax.json`/JSON-P and produces byte-identical order JSON for the same input.
- [ ] `RANDOM_NAMES`/`RANDOM_EMAILS` randomisation still applies to generated customer name/email.
- [ ] `audit-logging-library` 2.0.0 is a normal Maven dependency; no `system` scope and no `systemPath` remains.
- [ ] `OrderService` uses `StreamableAuditLogger` with `audit.stream.host`/`audit.stream.port` from configuration, not a local directory.
- [ ] `AuditEvent` is constructed as the 13-component record; `builder()`, `logSuccess`, `logFailure` are not used.
- [ ] `logEventAsync`/`logEventAsyncSafe` is used instead of the deprecated `logEvent`.
- [ ] `rgctl rules run rules` reports zero violations for `audit-logging-0001` … `audit-logging-0005`.
- [ ] Tests do not open TCP connections (dev/test profile uses a local fake collector or no-op).

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Story 2 complete (Java 21 toolchain — mandatory for the 2.0.0 jar, which uses a `record` and virtual threads).
- Story 3 complete (`Transformers` output feeds both REST and messaging paths that are already tested).
- A decision on where audit events go in each environment (collector host/port, or accepted no-op in dev).

---

### Steps to Reproduce / Implementation Notes
1. Rewrite `shoppingCartToJson`/`jsonToOrder` with Jackson (or `jakarta.json`), preserving keys and the entity shape inside `items`.
2. Replace `utils/Producers` with JBoss Logging injection at the 6 `@Inject Logger` sites.
3. Change the dependency to `com.enterprise:audit-logging-library:2.0.0` (regular scope) and make it resolvable in the container build.
4. Rewrite `OrderService.init()`/`cleanup()`: `StreamableAuditLogger`, stream config from MicroProfile Config, `@PreDestroy close()`.
5. Emit an audit event through `logEventAsync(new AuditEvent(...))` on the order path and handle the returned future without blocking the request.
6. Re-run `rgctl rules run rules` and attach the clean output to the PR.

---

### Dependencies
- Stories 2 and 3.
- Blocking for Story 5 (the checkout/consumer path emits the audit event).

---

### Test Cases
- Test 1: Golden-file comparison of `shoppingCartToJson` before and after for 3 carts (empty, single item, multi-item with promos).
- Test 2: `jsonToOrder` parses the documented payload and preserves `productSku`→`productId` mapping.
- Test 3: Against a fake TCP server, an emitted event arrives as one NDJSON line with all 13 record fields.
- Test 4: With the collector down, `logEventAsync` fails without breaking the checkout response (safe path).
- Test 5: `rgctl rules run rules` → `violations: []`.

---

### Notes / Comments
- `AuditConfiguration` keeps the file-oriented setters but adds stream properties; leftover file paths (`./device-inventory-audit-logs`) must not remain in code.
- `StreamableAuditLogger` throws `IllegalStateException("Audit logger is closed")` after close — the `@PreDestroy` path must be tested.
- The 2.0.0 jar references Jackson (`ObjectMapper`, `JavaTimeModule`); ensure `quarkus-rest-jackson` is present so the classes resolve.

---

## User Story 5: Move Business Logic to CDI and Reactive Messaging

**As a:** Developer
**I want:** the EJB and JMS programming model replaced by CDI beans, direct injection and SmallRye Reactive Messaging
**So that:** checkout, pricing and inventory updates work correctly across multiple replicas on the target platform

---

### Tasks
- [Task Group 5: Business Services](tasks.md#task-group-5-business-services-ejb--cdi-remote-lookup-removal-cart-state-messaging)

### Acceptance Criteria
- [ ] No `javax.ejb`/`jakarta.ejb` annotations remain except where CDI has no equivalent; `@Stateless` beans are `@ApplicationScoped` with explicit transaction boundaries.
- [ ] `ShippingServiceRemote` and the `ejb:/ROOT/ShippingService!...` JNDI lookup are gone; `ShippingService` is injected directly into `ShoppingCartService`.
- [ ] Cart state survives a rolling restart and is consistent across 2 replicas (documented model, e.g. cart-id-keyed store).
- [ ] `ShoppingCartOrderProcessor` publishes through an `Emitter` on channel `orders`, producing the unchanged JSON payload.
- [ ] `OrderServiceMDB` is an `@Incoming("orders")` consumer that saves the order and decrements inventory exactly as before.
- [ ] Duplicate delivery does not double-decrement inventory, or the duplication risk is explicitly documented and accepted.
- [ ] Two replicas consuming the same topic both process messages (competing-consumer parity with the README demo).

---

### Details
- **Priority:** High
- **Estimate:** 13 story points (largest story: 3 independent paradigm changes)

---

### Preconditions
- Stories 3 and 4 complete (entities, Flyway, `Transformers`, audit).
- A broker technology decision (AMQ Streams/Kafka vs AMQ Broker/AMQP) and namespace availability.
- Agreement on the cart-state model (in-memory keyed map vs Redis/Infinispan vs client-owned cart).

---

### Steps to Reproduce / Implementation Notes
1. Convert `CatalogService`, `ProductService`, `OrderService`, `PromoService`, `ShoppingCartOrderProcessor` to `@ApplicationScoped`; add `@Transactional` where the EJB container used to provide it.
2. Delete `ShippingServiceRemote`; remove `@Remote`; inject `ShippingService` in `ShoppingCartService`.
3. Implement the cart-state model; keep `cartId`/`tmpId` merge semantics used by the SPA.
4. Replace `JMSContext`/`@Resource Topic` with `@Channel("orders") Emitter<String>`.
5. Replace `@MessageDriven` with `@Incoming("orders")`, keeping `Transformers.jsonToOrder` → `save` → `updateInventoryItems`.
6. Configure channels for dev (in-memory/Dev Services) and prod (broker bootstrap, topic `orders`, consumer group).

---

### Dependencies
- Stories 3 and 4.
- Blocking for Story 6 (REST delegates to these services) and Story 8 (replica/broker deployment).

---

### Test Cases
- Test 1: `ShippingService` tier and insurance tables unchanged (characterization tests pass).
- Test 2: `PromoService.applyCartItemPromotions`/`applyShippingPromotions` produce identical totals for the seeded catalogue.
- Test 3: Cart created on replica A is visible/priced after a request routed to replica B (or failover behaviour matches the documented model).
- Test 4: Checkout emits the golden JSON to channel `orders`; the consumer persists and decrements.
- Test 5: Delivering the same message twice yields one decrement (or the documented accepted behaviour).
- Test 6: Low-stock branch (< 50) triggers the notification path, or the deletion of `InventoryNotificationMDB` is documented.

---

### Notes / Comments
- `ShoppingCartService` resolves `ShippingService` over JNDI **twice per pricing pass**; removing it also removes a failure mode and a latency source.
- `@Stateful` cart state is a pre-existing clustering bug (in-memory per JVM); porting it verbatim would preserve the bug across replicas.
- `InventoryNotificationMDB` contains WebLogic-only JNDI (`t3://localhost:7001`, `WLInitialContextFactory`) and is never activated — default is deletion with a recorded decision.

---

## User Story 6: Serve the Same REST API and SPA with Keycloak SSO

**As a:** Storefront user
**I want:** the existing web UI and `/services` API to work identically, with Keycloak login
**So that:** the migration is invisible to users and API consumers

---

### Tasks
- [Task Group 6: REST Layer, SPA Hosting and OIDC](tasks.md#task-group-6-rest-layer-spa-hosting-and-oidc)
- [Task Group 2](tasks.md#task-group-2-build-and-platform-foundation-java-21-quarkus-bom-jakarta-namespace) (static resource layout foundation)

### Acceptance Criteria
- [ ] All 9 endpoint methods exist at the same paths under `/services` with the same media types and JSON shapes.
- [ ] `CartEndpoint` is no longer `@SessionScoped`; cart behaviour comes from the store chosen in Story 5.
- [ ] The AngularJS SPA is served from `src/main/resources/META-INF/resources` and loads (`/`, `/app/*`, `/partials/*`, `/bower_components/*` return 200).
- [ ] `index.jsp` is replaced by static `index.html`; SSO bootstrap does not depend on server-side session creation.
- [ ] `health.jsp` is replaced by `/q/health` (live/ready/started).
- [ ] No `localhost` Keycloak URL remains in the repository; OIDC settings come from configuration.
- [ ] Login, logout and authenticated checkout work against Keycloak realm `eap`, client `eap-app`.
- [ ] `coolstore.json` (`MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT`, `SSO_ENABLED`) is environment-configurable, not hardcoded.

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 5 complete (services and cart behaviour available for injection).
- Keycloak realm `eap` and client `eap-app` importable; agreement on whether `/services/**` becomes authenticated (behaviour change).
- Decision on whether `bower_components` is kept in full or pruned.

---

### Steps to Reproduce / Implementation Notes
1. Fix resource scopes and verify all 9 methods against the characterization suite.
2. Move web assets to `META-INF/resources`; replace the two JSPs.
3. Externalize `keycloak.json`/OIDC config; add `quarkus-oidc` for backend protection if approved.
4. Generate `coolstore.json` from configuration at runtime.
5. Run the SPA end to end in a browser against Keycloak.

---

### Dependencies
- Story 5. Requires the identity owner for realm/client changes (redirect URIs).
- Blocking for Stories 7 and 8 (image/manifests need the final web layout and env contract).

---

### Test Cases
- Test 1: REST Assured contract suite for all 9 endpoints passes unchanged from Story 1.
- Test 2: `GET /` returns the SPA shell; static assets resolve with 200 and correct content types.
- Test 3: Unauthenticated checkout redirects to Keycloak; authenticated checkout completes and shows the order confirmation.
- Test 4: Cart merge (`POST /services/cart/{cartId}/{tmpId}`) still transfers items after login, matching `app/services/cart.js` behaviour.
- Test 5: `/q/health/ready` returns UP when the database is reachable and DOWN when it is not.

---

### Notes / Comments
- Today only the SPA enforces login; `/services/**` is unauthenticated. Protecting it is a security improvement **and** a behaviour change — needs explicit sign-off and a client-credentials/token path for any API consumer.
- `index.jsp`'s only job was `request.getSession(true)`; removing JSP support must not break the SPA's cart/session assumptions.
- AngularJS 1.x and `bower_components` are EOL and may trip image scanning; document as accepted risk with a follow-up epic.

---

## User Story 7: Package a Hardened Container Image

**As a:** Platform Engineer
**I want:** a non-root, reproducible container image with all configuration externalized
**So that:** the application can be deployed to OpenShift without baking secrets or environment assumptions into the artifact

---

### Tasks
- [Task Group 7: Containerization](tasks.md#task-group-7-containerization)

### Acceptance Criteria
- [ ] A multi-stage `Containerfile` builds the Quarkus fast-jar on UBI 9 / OpenJDK 21 and runs as a fixed non-root UID.
- [ ] The image starts with only environment variables — no edits to `application.properties`, no `localhost` defaults required.
- [ ] `DB_PASSWORD`, OIDC client secret, and the audit endpoint are supplied at runtime (Secret/ConfigMap), never baked in.
- [ ] No `systemPath`/`lib/*.jar` dependency is required at build time (audit jar resolved via Maven).
- [ ] Image scan (Trivy/ACS) shows no unaccepted HIGH/CRITICAL findings; an SBOM is generated.
- [ ] `@QuarkusIntegrationTest` passes against the built image.

---

### Details
- **Priority:** Medium
- **Estimate:** 5 story points

---

### Preconditions
- Story 6 complete (final web layout and configuration surface).
- Registry access and a service account pull secret.
- Vulnerability thresholds agreed with security.

---

### Steps to Reproduce / Implementation Notes
1. Write the multi-stage `Containerfile` (build → runtime), copy `target/quarkus-app/`.
2. Set UID/GID, `JAVA_OPTS=-XX:MaxRAMPercentage=75`, read-only-friendly filesystem.
3. Document and verify the env var contract end to end.
4. Build, scan, generate SBOM, run integration tests against the image.

---

### Dependencies
- Story 6. Blocking for Story 8.

---

### Test Cases
- Test 1: `podman run` with the full env set serves `/` and `/q/health/ready` returns UP.
- Test 2: Missing `DB_URL` fails fast with a clear configuration error, not a silent default.
- Test 3: Image runs as UID 185 and cannot write outside `/tmp`.
- Test 4: `@QuarkusIntegrationTest` suite passes against the image.
- Test 5: Scan output contains no unaccepted HIGH/CRITICAL vulnerabilities.

---

### Notes / Comments
- The current `pom.xml` uses `system` scope with `${project.basedir}/lib/audit-logging-library-1.0.0.jar`; container builds cannot rely on that path, hence the `maven-install-plugin` step.
- Keeping the full `bower_components` tree inflates the image substantially (the repo's vendored JS dominates the 1,232 indexed files); measure and record the trade-off.

---

## User Story 8: Deploy the Application on OpenShift

**As a:** Platform Engineer
**I want:** declarative OpenShift manifests, health probes, secrets and a delivery pipeline for the app and its dependencies
**So that:** the application runs reliably with zero-downtime releases and a tested rollback

---

### Tasks
- [Task Group 8: OpenShift Deployment](tasks.md#task-group-8-openshift-deployment)

### Acceptance Criteria
- [ ] Manifests (Deployment with ≥ 2 replicas, Service, Route, ConfigMap, Secret, NetworkPolicy, HPA) apply cleanly to a clean namespace.
- [ ] `startupProbe`/`readinessProbe`/`livenessProbe` use `/q/health/started|ready|live`; a failing database makes readiness DOWN without restart loops.
- [ ] Rolling updates use `maxUnavailable: 0` and a termination grace period long enough to finish in-flight orders.
- [ ] PostgreSQL, the messaging broker (`orders` topic) and Keycloak (realm `eap`) are provisioned or bound and reachable from the app.
- [ ] Flyway migrates at application start on a fresh namespace.
- [ ] The Azure Web App workflow and its publish-profile secret dependency are replaced by an image build + OpenShift deploy pipeline.
- [ ] A documented cutover and a rehearsed rollback to JBoss EAP 7.4 exist.

---

### Details
- **Priority:** High
- **Estimate:** 8 story points

---

### Preconditions
- Story 7 complete (published image).
- Cluster namespace with quotas, registry pull secret, and DNS/Route domain.
- Keycloak redirect URIs updated for the Route hostname.

---

### Steps to Reproduce / Implementation Notes
1. Author manifests with Kustomize overlays per environment.
2. Wire probes, graceful shutdown and rolling update strategy.
3. Provision PostgreSQL, broker and Keycloak; import the realm.
4. Replace the GitHub Actions workflow with build → test → image → deploy (or GitOps commit).
5. Rehearse cutover and a rollback drill.

---

### Dependencies
- Story 7. Blocking for Story 9.

---

### Test Cases
- Test 1: Fresh namespace deploy → pods Ready, Route serves the SPA, `/q/health/ready` UP.
- Test 2: Kill the PostgreSQL pod → readiness goes DOWN, then recovers without manual restart.
- Test 3: Rolling update during continuous checkout → zero failed requests, no duplicate orders.
- Test 4: 2 replicas both consume from `orders` (competing consumers) and consumer lag returns to zero.
- Test 5: Rollback drill: switch Route back to JBoss EAP 7.4 with no schema change required.

---

### Notes / Comments
- The repository's `pom.xml` still contains the `<!-- TODO: Add OpenShift profile here -->` placeholder — this story is where that TODO is finally discharged (via container-image config in Story 2/7 and manifests here).
- Flyway is shared between old and new deployments, so rollback is code-only as long as no new migration file is added in this story.
- Cluster password / `jms-topic add` / `data-source add` steps from the README disappear; the README must stop instructing operators to run them.

---

## User Story 9: Prove Parity, Cut Over and Hand Over

**As a:** Product Owner
**I want:** verified behavioural parity, a completed cutover and complete documentation
**So that:** the JBoss EAP deployment can be retired without customer impact and the team can operate the new stack

---

### Tasks
- [Task Group 9: Verification, Cutover and Handover](tasks.md#task-group-9-verification-cutover-and-handover)

### Acceptance Criteria
- [ ] The full regression suite passes on OpenShift: characterization, unit, integration (against the image), and E2E (browse → cart → promo → checkout → order → inventory).
- [ ] Keycloak login and the authenticated checkout journey pass in the browser.
- [ ] p95 latency for `/services/products` and checkout regresses by no more than 10 % versus the Story 1 baseline; memory and startup are better than the JBoss baseline.
- [ ] TL/JBoss-specific artifacts are removed: `WEB-INF/web.xml`, `META-INF/persistence.xml`, `weblogic/**`, `lib/audit-logging-library-1.0.0.jar`, the Azure workflow, the `jboss-cli` README instructions.
- [ ] `README.md`, `MIGRATION.md`, and `docs/{baseline,data,audit,services,web,container,openshift}.md` are complete, reviewed, and owned.
- [ ] The team has completed a Quarkus 3 / CDI / reactive messaging / OpenShift enablement session.
- [ ] Production cutover completed with the JBoss EAP WAR kept deployable for 30 days as the rollback path.

---

### Details
- **Priority:** High
- **Estimate:** 5 story points

---

### Preconditions
- Story 8 complete (deployed, probes green, pipeline working).
- Production change approval and a maintenance/communication plan.
- Baseline performance data from Story 1 available for the comparison report.

---

### Steps to Reproduce / Implementation Notes
1. Run the complete suite against stage; fix any parity gaps before touching production.
2. Run the load comparison and publish a before/after table.
3. Rehearse cutover and one rollback under load.
4. Remove legacy artifacts in a single cleanup PR.
5. Publish docs, run enablement, then execute the production cutover and monitor for 30 days.

---

### Dependencies
- Story 8.
- Identity owner (final realm/client configuration), DBA (backup verification), and release management (change window).

---

### Test Cases
- Test 1: E2E user journey with SSO completes; order appears in `ORDERS` and inventory decreased.
- Test 2: Performance report shows p95 within 10 % of baseline for both measured endpoints.
- Test 3: Cutover under load: zero failed checkouts, zero duplicate orders, no cart loss.
- Test 4: Rollback to JBoss EAP 7.4 succeeds and the application serves traffic again.
- Test 5: `grep -r "javax\\." src/main/java` and `grep -r "jboss\\|weblogic" src README.md` return only accepted, documented exceptions.

---

### Notes / Comments
- Accepted risks to document explicitly: AngularJS 1.x + `bower_components` EOL, deprecated `logEvent` still present in the 2.0.0 API, and the cart-state model's consistency guarantees.
- The audit collector is an external dependency — if it does not exist in production, the `logEventAsyncSafe` path and its failure mode must be documented as an operational assumption.
