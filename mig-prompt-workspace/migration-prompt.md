# Migration Prompt

## Current Application Summary

The repository is the **CoolStore Monolith** (`com.redhat.coolstore:monolith:1.0.0-SNAPSHOT`),
a Java EE 7 storefront application packaged as a `war` and deployed to **JBoss EAP 7.4**
(standalone-full-ha, two-node cluster) with **Keycloak 20.0.5** for SSO and **PostgreSQL**
for persistence. It is a classic three-tier monolith: an AngularJS 1.x single-page front end
served from `src/main/webapp`, a JAX-RS REST layer under `/services`, a business layer built
from stateless/stateful EJBs and a JMS message-driven bean, and a JPA/Hibernate persistence
layer against the `CoolstoreDS` JBoss datasource. Schema and seed data are managed by Flyway
migrations (`src/main/resources/db/migration/V1_1`, `V1_2`).

The functional flows are: browse the product catalogue, add/remove items in a shopping cart
(cart state is held in memory by a `@Stateful` bean), price the cart with promotions and
shipping/insurance calculations, check out by publishing an order to the JMS topic
`topic/orders`, then persist the order and decrement inventory from a message-driven bean.
The README documents a two-node clustering scenario whose shared goal is that **both nodes
consume order messages** — i.e. competing consumers on a topic backed by JBoss ActiveMQ.

The application is small in application code (1,785 LOC / 30 Java files / 27 classes in
`com.redhat.coolstore`) but has a large vendored web asset tree (`src/main/webapp/bower_components`,
AngularJS/PatternFly/jQuery/DataTables), which dominates the indexed repository (1,235 files,
35,985 symbols). An internal audit-logging library is consumed from a **`system`-scoped jar**
under `lib/` and is being upgraded from 1.0.0 to 2.0.0 as part of this migration.

**Technology Stack:**
- Java 8 source/target (`maven-compiler-plugin` source/target `1.8`); README assumes OpenJDK 17
  at runtime; CI workflow (`.github/workflows/main_clustertesting(dev).yml`) pins Java 11.
  All three disagree — the build has no enforced toolchain.
- Java EE 7 (`javax.*`): EJB 3.2 (`javax.ejb.*`), JPA 2.1 (`javax.persistence.*`),
  JAX-RS 2.0 (`javax.ws.rs.*`), CDI 1.1 (`javax.inject`, `javax.enterprise.*`),
  Bean Validation/JMS 2.0 (`javax.jms.*`), JSON-P (`javax.json.*`), JAXB (`javax.xml.bind`),
  JNDI/RMI (`javax.naming`, `javax.rmi`).
- Persistence: Hibernate ORM via `persistence.xml` (persistence unit `primary`),
  JBoss datasource `java:jboss/datasources/CoolstoreDS`, PostgreSQL 42.5.4 driver,
  Flyway 4.1.2 (`new Flyway()` API, run from an EJB `@Singleton @Startup`).
- Messaging: JMS 2.0 — `@MessageDriven` consumer + `JMSContext`/`@Resource(lookup="java:/topic/orders")`
  producer; one legacy WebLogic-style `InventoryNotificationMDB` using `t3://localhost:7001` JNDI.
- Auth: Keycloak 20 via the legacy JS adapter (`keycloak.json` + `keycloak.min.js`),
  realm `eap`, public client `eap-app`, plus a 59 KB `realm-export.json` at repo root.
- Audit: `com.enterprise:audit-logging-library:1.0.0` (POJO `AuditEvent` + `builder()`,
  `FileSystemAuditLogger` writing local files) via `lib/audit-logging-library-1.0.0.jar`.
- Build: Maven, `maven-war-plugin` 3.2.0, `finalName` `ROOT`, `maven.test.skip=true`.

**Architecture:**
- Monolithic layered architecture, single deployable WAR (`ROOT.war`).
- Package structure (only 4 functional packages, all in `com.redhat.coolstore`):
  - `model` (8 classes): JPA entities `CatalogItemEntity`, `InventoryEntity`, `Order`,
    `OrderItem` + DTOs `Product`, `Promotion`, `ShoppingCart`, `ShoppingCartItem`.
  - `persistence` (1 class): `Resources` — `@Dependent` CDI producer for `EntityManager`.
  - `rest` (4 classes): `RestApplication` (`@ApplicationPath("/services")`),
    `CartEndpoint` (`/cart`, `@SessionScoped`), `ProductEndpoint` (`/products`),
    `OrderEndpoint` (`/orders`).
  - `service` (10 classes): `CatalogService` (`@Stateless`), `ProductService` (`@Stateless`),
    `OrderService` (`@Stateless` + audit logger), `PromoService` (`@Stateless`),
    `ShippingService` (`@Stateless @Remote`) + `ShippingServiceRemote`,
    `ShoppingCartService` (`@Stateful`, holds the cart), `ShoppingCartOrderProcessor`
    (`@Stateless`, JMS producer), `OrderServiceMDB` (`@MessageDriven`),
    `InventoryNotificationMDB` (legacy WebLogic JNDI client, not annotated).
  - `utils` (4 classes): `Transformers` (JSON-P ↔ model), `Producers` (`Logger` producer),
    `DataBaseMigrationStartup` (`@Singleton @Startup`, Flyway), `StartupListener`
    (extends `weblogic.application.ApplicationLifecycleListener` — vendor-specific dead code).
- Integration points and coupling (from the rgctl call graph: 138 intra-Java `CALLS` edges):
  - `ShoppingCart` is the hub type — `getShoppingCartItemList` 7 incoming calls,
    `getCartItemTotal` 4, `setShippingTotal` 3; `ShoppingCartService.getShoppingCart` 5,
    `CartEndpoint.add` 5, `ShoppingCartService.priceShoppingCart` 4.
  - Highest fan-out: `ShoppingCartService.priceShoppingCart` (13 calls),
    `CartEndpoint.add` (12), `Transformers.jsonToOrder` (11), `Transformers.toProduct` (8),
    `ShoppingCartService.initShoppingCartForPricing` (10).
  - The four tightest file-level couplings are `ShoppingCartService → ShoppingCart` (15 calls),
    `CartEndpoint → ShoppingCartService` (10), `CartEndpoint → ShoppingCart` (9),
    `Transformers → Order` (8).
  - Remote EJB lookup: `ShoppingCartService` resolves `ShippingService` through
    `org.wildfly.naming.client.WildFlyInitialContextFactory` and the URL
    `ejb:/ROOT/ShippingService!com.redhat.coolstore.service.ShippingServiceRemote` on every
    pricing call.
  - External systems: PostgreSQL (`CoolstoreDS`), JMS topic `topic/orders`, Keycloak
    `http://localhost:8081/` (hardcoded in `keycloak.json`), filesystem audit log directory
    `./device-inventory-audit-logs`.
  - `OrderServiceMDB` calls `CatalogService.updateInventoryItems` → `EntityManager.merge`;
    `ShoppingCartOrderProcessor` publishes JSON produced by `Transformers.shoppingCartToJson`.

**Scale:**
- Full-repository index: 1,235 files → 35,985 nodes, 125,929 edges (dominated by the
  vendored `bower_components` tree).
- Java-scoped index (`rgctl discover . -l java --with-cfg --with-security --with-harmonic
  --export-migration-hints`): 30 files → 680 nodes, 1,960 edges; 164 functions; 539
  communities (over-fragmented, not usable as subsystem boundaries); 6 circular dependencies.
- 1,785 LOC Java, 463 LOC application JavaScript, plus the vendored `bower_components` tree.
- 4 REST resource classes, 9 JAX-RS endpoint methods, 1 JMS topic, 4 database tables.
- No test sources exist (`src/test` absent, `maven.test.skip=true`).
- Single team, no external owners; repository is a reference/demo-style codebase.

**Knowledge-graph evidence (rgctl v0.4.20):**

| Signal | Command | Result |
|---|---|---|
| Index coverage | `rgctl -f json metrics --pagerank` | 680 nodes / 1,960 edges (Java), 164 functions |
| Subsystem map | `rgctl -f json communities list` | 539 communities, modularity 0.36 — no meaningful subsystem split; Java packages are the unit of work |
| Coupling | `rgctl -f json relations --edge calls --limit 2000` | 138 intra-Java `CALLS` edges; top fan-in `ShoppingCart.getShoppingCartItemList` (7), `ShoppingCartService.getShoppingCart` (5), `CartEndpoint.add` (5), `ShoppingCartService.priceShoppingCart` (4); top fan-out `ShoppingCartService.priceShoppingCart` (13), `CartEndpoint.add` (12), `Transformers.jsonToOrder` (11) |
| Migration roadmap | `rgctl discover . -l java --export-migration-hints` → `.rgctl/migration_plan.json` (7 steps) | Step 1 `weblogic.i18n.logging`, 2 `weblogic.application`, 3 `com.redhat.coolstore.persistence`, 4 `rest`, 5 `utils`, 6 `service`, 7 `model` (priority-ranked, see planning note) |
| Rule violations | `rgctl -f json rules run rules` | 5 rules evaluated, 1 violation: `audit-logging-0003` at `OrderService.java:5` (`FileSystemAuditLogger` import) |
| Security | `--with-security` | 5 potential secrets flagged in the full-repo index; review candidates are `keycloak.json`, `realm-export.json`, `coolstore.json` and the README's `postgresPW` — all credentials must move to ConfigMap/Secret |
| Data flow | `--with-taint` | 1 taint flow reported by the Phase-1 discover (1 vulnerable); the planner did not attribute it to a Java sink — re-verify with `rgctl taint --sink <symbol>` before relying on it |

*Planning note:* rgctl ranks `rest` above `service` and `model` on priority score, but the call
graph is `rest → service → (model, utils, persistence)`; the plan below therefore follows
**dependency (build) order** and uses the rgctl roadmap only for the "vendor stubs first"
signal (steps 1–2) and for hotspot prioritisation of tests.

## Target Platform and Technologies

**Target Stack:**
- **Quarkus 3.x** (latest stable 3.x LTS line; Jakarta EE 10) — repository signals:
  `.konveyor/profiles/quarkus-profile/profile.yaml` selects
  `konveyor.io/target=quarkus || quarkus3 || jakarta-ee`, and `rules/rule.yaml` labels every
  rule `konveyor.io/source=openjdk8` / `konveyor.io/target=quarkus3`.
- **Java 21** (required by `audit-logging-library` 2.0.0, which ships a `record` `AuditEvent`
  and a `StreamableAuditLogger` built on `Executors.newVirtualThreadPerTaskExecutor()`).
- **Jakarta EE 10 namespaces** (`jakarta.*`): Jakarta REST 3.1, Jakarta Persistence 3.1,
  CDI 4.0, Jakarta Annotation 2.1, Jakarta JSON Processing 2.1, Jakarta Bean Validation 3.0.
- Persistence: **Hibernate ORM 6 + Agroal** datasource configured in `application.properties`;
  **Quarkus Flyway** extension with `quarkus.flyway.migrate-at-start=true` replacing the
  `@Singleton @Startup` migration bean; PostgreSQL retained.
- Messaging: replace JMS/ActiveMQ with **SmallRye Reactive Messaging** (`quarkus-messaging`)
  over an in-cluster broker (Kafka via Strimzi/AMQ Streams, or AMQP 1.0 via AMQ Broker)
  preserving the `topic/orders` contract and competing-consumer semantics.
- Auth: **Quarkus OIDC** (`quarkus-oidc`) or bearer/`quarkus-keycloak-authorization` on the
  service side and Keycloak 20+ (or Keycloak 24+ / RHBK) on the identity side; the legacy
  `keycloak.json` adapter config is replaced by OIDC discovery + environment configuration.
- Audit: `com.enterprise:audit-logging-library:2.0.0` consumed as a normal Maven dependency
  (no `system` scope), with `StreamableAuditLogger` sending NDJSON over TCP to a collector
  (`audit.stream.host` / `audit.stream.port`, default `localhost:9000`).
- Build: Maven with the Quarkus BOM and `quarkus-maven-plugin`, producing a fast-jar/UBI
  container image via `quarkus.container-image` or an explicit multi-stage Containerfile;
  WAR packaging and `maven-war-plugin` dropped.
- Front end: keep the AngularJS SPA functionally intact, served as Quarkus static resources
  from `src/main/resources/META-INF/resources` (JSPs `index.jsp` / `health.jsp` must be
  replaced — Quarkus has no JSP support).

**Migration rationale:** the repository already carries a Quarkus/Konveyor migration profile
and Konveyor rules targeting `quarkus3` from `openjdk8`, and the audit library upgrade is
gated on Java 21. Moving to Quarkus 3 + Java 21 removes the JBoss EAP dependency, gives a
small memory footprint and fast startup, and provides first-class container/Kubernetes support
for the OpenShift target, while keeping the same PostgreSQL schema and REST contracts so the
existing SPA and any API consumers keep working.

**Deployment Platform:**
- Red Hat OpenShift 4.x (latest stable), Kubernetes-based container platform.
- Workloads: one application Deployment (Quarkus app serving SPA + REST), one PostgreSQL
  instance (or an operator-provisioned database such as Crunchy/CloudNativePG), the messaging
  broker, and an external/realm-configured Keycloak, plus an audit log collector endpoint.
- CI/CD: OpenShift Pipelines (Tekton) provided by the migration platform, building the image
  and applying manifests via GitOps; `.github/workflows/` is out of scope and must not change.

**Infrastructure Requirements:**
- Container runtime: Podman (local), UBI 9 / OpenJDK 21 base images.
- Orchestration: Kubernetes on OpenShift; `Route` for ingress, `Deployment` with
  `readinessProbe`/`livenessProbe` on `/q/health/ready` and `/q/health/live`.
- Configuration: `ConfigMap` + `Secret` for datasource URL/credentials, OIDC issuer/client,
  audit stream host/port, messaging bootstrap servers.
- Image registry: OpenShift internal registry; images scanned (Trivy/`oc image` scanning)
  before promotion.
- 12-factor configuration: no `localhost` literals, no credentials in the repo.

## Migration Approach

**Strategy:** Phased, layer-by-layer in-place modernization of the single deployable
(a "vertical slice" per phase), ending in containerization and OpenShift cutover.
This is *not* a microservice decomposition and *not* an unplanned big bang.

**Reasoning:**
- The application is small (1.8 K LOC) and tightly coupled (`ShoppingCart` is a 36-call hub,
  `ShoppingCartService` reaches `ShippingService` over remote EJB), so splitting into services
  would add risk without business value.
- The dominant risks are mechanical-but-pervasive namespace changes, three behavioural
  rewrites (EJB → CDI state, JMS → reactive messaging, remote EJB lookup → direct injection),
  and the audit library API/Java-21 requirement. Sequencing those as separate reviewable
  increments keeps the build green after every phase.
- There are **no tests today** and `mvn package` skips them, so characterization work must
  land before behaviour changes.
- Deployment today requires a hand-configured JBoss (`jboss-cli` datasource, `jms-topic`,
  cluster password), which OpenShift/Quarkus eliminates — a clean cutover is achievable once
  the containerized app passes the same end-to-end flows.

**Phases:**
1. **Baseline & guardrails** — build reproducibly, capture characterization tests for the
   REST API and pricing/checkout behaviour against the current JBoss deployment.
2. **Build & platform foundation** — Java 21 toolchain, Quarkus BOM, `jakarta.*` namespace
   sweep, config/profiles, remove WebLogic artifacts.
3. **Domain & persistence** — JPA entities to Jakarta Persistence 3.1, DTOs, `Resources`
   producer removal, `application.properties` datasource, Flyway 10 migration.
4. **Utilities & cross-cutting** — `Transformers` (JSON-P → Jackson), `Producers`,
   audit-logging-library 2.0.0 upgrade.
5. **Business services** — EJB → CDI, remote EJB lookup removal, `@Stateful` cart
   re-modelling, messaging rewrite (MDB → reactive messaging).
6. **REST layer & SPA hosting** — Jakarta REST resources, `@SessionScoped` resource fix,
   static SPA/JSP replacement, OIDC wiring.
7. **Containerization** — Containerfile, image hardening, registry push, digest pinning.
8. **OpenShift deployment** — manifests, probes, secrets, routes, autoscaling, pipeline.
9. **Verification & cutover** — E2E, performance baseline, rollback plan, docs, handover.

*(The nine phases map 1:1 to the nine task groups in `mig-plan-workspace/tasks.md` and to the
nine user stories in `mig-plan-workspace/UserStory.md`.)*

**Key Considerations:**
- `javax.*` → `jakarta.*` touches every Java file including annotations
  (`@Stateless`, `@Entity`, `@Path`, `@Inject`, `@MessageDriven`, `@Resource`).
- `@SessionScoped` on `CartEndpoint` (a JAX-RS resource) is not a valid Quarkus bean scope
  for REST resources; cart state must move to an explicit session/cookie/Redis-backed store
  or a client-owned cart id. Current in-memory `@Stateful` cart also breaks on a 2-node
  cluster — this is an existing latent bug the migration should fix rather than port.
- Remote EJB invocation via `ejb:/ROOT/ShippingService!...` must become a plain CDI injection;
  `PortableRemoteObject`/`javax.rmi` disappears.
- JMS topic semantics (competing consumers across replicas, at-least-once) must be preserved
  with SmallRye Reactive Messaging + a durable broker; the message payload (`Transformers`
  JSON: `orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`,
  `shippingFee`, `shippingDiscount`, `items[].productSku/quantity`) is the contract.
- Flyway: `new Flyway()`/`setDataSource()`/`baseline()` are removed in Flyway 9/10; the
  migration bean must be deleted in favour of `quarkus-flyway`, and existing V1_1/V1_2
  checksums must be preserved so already-migrated databases do not re-run or fail.
- `audit-logging-library` 2.0.0 changes: `AuditEvent` is a **record** (14 components:
  `timestamp, eventType, userId, sessionId, application, component, action, resource, result,
  message, details, correlationId, sourceIp, userAgent`), `builder()` is gone,
  `FileSystemAuditLogger` is replaced by `StreamableAuditLogger`, `logEvent(...)` is
  `@Deprecated` in favour of `logEventAsync(...)`/`logEventAsyncSafe(...)`, and
  `AuditConfiguration` gains `streamHost/streamPort/streamProtocol/streamBufferSize/
  streamReconnectMs` (`audit.stream.*` properties). Only one violation is currently reported
  by `rgctl rules run rules` (`audit-logging-0003`, `OrderService.java:5`), but the whole
  audit call site must be rewritten, not just the import. Note that the current code only
  *constructs* `FileSystemAuditLogger` in `@PostConstruct` and closes it in `@PreDestroy`;
  no `AuditEvent` is ever emitted. The migration must decide explicitly whether to start
  emitting audit events (e.g. `logEventAsync` on `OrderService.save`) or drop the logger.
- JSPs (`index.jsp`, `health.jsp`) are unsupported on Quarkus; `index.jsp` also creates the
  HTTP session the SPA relied on. Static `index.html` + OIDC JS client replaces it; health
  becomes SmallRye Health.
- `pom.xml` has an empty `<profiles><!-- TODO: Add OpenShift profile here -->` block —
  the OpenShift build/profile is explicitly unfinished work in this repo.
- Database/SQL: `int4`, `int8`, `float8` PostgreSQL-specific types and quoted mixed-case
  columns (`itemId`, `orderId`) must stay compatible; `ORDERS`/`ORDER_ITEMS` DDL is unchanged.
- Front end: AngularJS 1.x and the vendored `bower_components` tree are EOL; the migration
  must keep them working as static assets, and pruning/relocating them is a follow-up
  improvement, not a prerequisite.

## Required Deliverables

### 1. Containerization
- A `Containerfile` (multi-stage: Maven build stage on `ubi9/openjdk-21`, runtime stage on
  `ubi9/openjdk-21-runtime` or the Quarkus JVM base image) producing a non-root, small image.
- No `system`-scoped jars at build time: `audit-logging-library` 2.0.0 must be resolvable from
  a Maven repository (install into the build stage or use `maven-install-plugin`), and the
  image must not rely on a local `lib/` path baked into the repo.
- Image security scanning (Trivy or OpenShift ACS) with no HIGH/CRITICAL findings carried
  into the deployment; explicit SBOM/dependency report.
- Image published to the OpenShift internal registry and tagged per commit.

### 2. OpenShift Deployment
- Kubernetes manifests (or Helm/Kustomize) for: the Quarkus application `Deployment`,
  `Service`, `Route`, `ConfigMap`, `Secret`, resource requests/limits, HPA.
- `readinessProbe` → `/q/health/ready`, `livenessProbe` → `/q/health/live`,
  `startupProbe` where relevant; graceful shutdown configured so in-flight orders complete.
- Datasource credentials, OIDC client secret, and audit stream endpoint sourced from
  `Secret`/`ConfigMap` — never baked into the image or `application.properties`.
- PostgreSQL deployed in-cluster (or bound to a managed instance) with the existing Flyway
  schema; Flyway runs at application start.
- A messaging broker deployed or bound (Kafka/AMQ) with the `orders` topic/channel and
  consumer group semantics equivalent to the current JMS topic.
- Rolling deployment strategy with zero-downtime cutover; documented rollback to the WAR on
  JBoss EAP 7.4 (`ROOT.war`) until the Quarkus deployment is accepted.
- **Do not create or modify anything under `.github/workflows/`** — CI/CD is owned by the
  migration platform (Tekton pipelines on OpenShift) and a pushed workflow file is rejected.
  The existing Azure Web App workflow is left in place, superseded by the platform pipeline;
  the JBoss `jboss-cli` setup steps in the README become obsolete.

### 3. Test Coverage
- **Characterization tests** (written *first*, against the current behaviour):
  - REST contracts for `/services/products`, `/services/products/{itemId}`,
    `/services/cart/{cartId}`, `/services/cart/{cartId}/{itemId}/{quantity}` (POST/DELETE),
    `/services/cart/{cartId}/{tmpId}`, `/services/cart/checkout/{cartId}`,
    `/services/orders`, `/services/orders/{orderId}` — including JSON field names and
    media types.
  - Pricing rules: `PromoService` cart-item/shipping promotions, `ShippingService`
    tiered shipping fees (2.99/4.99/6.99/8.99/10.99) and insurance (2%/1.5%/1% of total,
    `HALF_UP` to 2 decimals), 25-unit free-insurance threshold.
  - Checkout message payload produced by `Transformers.shoppingCartToJson`, and inventory
    decrement + low-stock threshold (50) behaviour in the order consumer.
  - Flyway migration from an empty database and from an already-migrated database.
- **Unit tests**: JUnit 5 + Quarkus Test; target ≥ 70 % line coverage on `service`, `rest`,
  and `utils` packages (they are small, so this is realistic).
- **Integration tests**: `@QuarkusTest` with Dev Services for PostgreSQL and the messaging
  channel; `@QuarkusIntegrationTest` against the built container image.
- **End-to-end tests**: the full browse → add to cart → checkout → order persisted →
  inventory decremented journey, including Keycloak login (browser-based or OIDC token flow).
- **Performance baseline and target**: measure current p95 latency and throughput for
  `/services/products` and checkout; target no regression beyond 10 % and JVM/image memory
  below the current JBoss footprint (record measured values from the baseline phase).

### 4. Detailed Migration Plan
- Phase-by-phase breakdown (see Approach) with dependency-ordered tasks, explicit
  prerequisites, and definition of done per task.
- Risk register with likelihood/impact and mitigations, in particular: Jakarta namespace
  sweep completeness, cart/session semantics, JMS → reactive messaging parity, audit library
  wire-format/collector readiness, Flyway checksum stability, AngularJS/keycloak.js
  compatibility, and vendor dead-code removal.
- Rollback procedures per phase, and a documented fallback to JBoss EAP 7.4.
- Monitoring/observability: Quarkus health/metrics (`/q/health`, `/q/metrics`), structured
  logging, OpenShift alerts for error rate, checkout failures, and consumer lag.
- Documentation: updated README (Quarkus build/run, OpenShift deploy, Keycloak realm setup),
  environment variable reference, and a developer onboarding note for the new stack.
- Training: short enablement for the team on Quarkus 3, CDI scopes, SmallRye Reactive
  Messaging, and OpenShift deployment.

## Success Criteria

- The Quarkus 3 / Java 21 application builds with `mvn verify`, all tests green, and no
  `javax.*` EE imports or WebLogic/JBoss-specific code remain in `src/main/java`.
- All REST endpoints are functionally identical (same paths, payloads, status codes) and the
  existing AngularJS SPA works unchanged against the new backend, including Keycloak SSO.
- Checkout publishes to the broker and the order consumer persists the order and decrements
  inventory, with competing consumers working across ≥ 2 replicas.
- `audit-logging-library` 2.0.0 is consumed as a normal dependency; audit events are emitted
  asynchronously via `logEventAsync` and received by the TCP collector.
- Flyway applies cleanly on an empty database and on an existing one without checksum errors.
- The container image runs as non-root on OpenShift with health probes passing, configuration
  and credentials externalized to ConfigMap/Secret, and zero-downtime rolling updates.
- Test coverage targets met; characterization tests prove behavioural parity.
- README/docs updated, rollback plan rehearsed, team enabled on the new stack.

## Constraints and Risks

**Constraints:**
- No test sources exist and the build currently skips tests — characterization work is on the
  critical path and cannot be parallelized with behavioural changes.
- Java 21 is mandatory at compile time because of `audit-logging-library` 2.0.0, so the
  upgrade of that dependency cannot be deferred to the end.
- The repository pins the audit jar with `system` scope and a `${project.basedir}/lib` path;
  it must be moved to a resolvable repository/installed artifact for container builds.
- PostgreSQL schema and Flyway history must be preserved; the database is not to be recreated.
- Keycloak realm `eap`, public client `eap-app`, and the SPA's OIDC flow are external
  contracts owned outside this repository (`realm-export.json` is the only in-repo copy).
- The vendored `bower_components` tree and AngularJS 1.x stay in scope only as static assets;
  no front-end framework rewrite is required.
- Timeboxed migration: this is a small application and should complete in a few iterations;
  scope creep into microservice decomposition is out of scope.

**Risks:**
- *Jakarta namespace sweep* — a missed `javax.*` import (e.g. `javax.json` in `Transformers`,
  `javax.xml.bind` in `InventoryEntity`) fails at runtime, not compile time, if the javax artifact
  lingers on the classpath. Mitigation: full-tree grep gate in CI plus a build without any
  `javax.*` EE API dependency.
- *Cart/session semantics* — `@Stateful` `ShoppingCartService` and `@SessionScoped`
  `CartEndpoint` keep per-user carts in JVM memory; on Quarkus or in a 2-replica deployment
  carts are lost or split. Mitigation: explicit cart-id based store (client-provided id or
  Redis/Infinispan-backed session) and tests covering replica failover.
- *JMS → reactive messaging parity* — ordering, redelivery and the "both nodes consume"
  clustering demo have no direct 1:1 mapping. Mitigation: define the channel/topic contract
  first, add consumer-group configuration, and test with 2 replicas.
- *Audit library rewrite* — record constructor with 14 components, async send, and a TCP
  collector that may not exist in the target environment; `logEvent` is deprecated, not
  removed. Mitigation: introduce a thin adapter, configure `audit.stream.*`, and provide a
  collector (or a no-op fallback profile) for local/dev.
- *Flyway upgrade* — API removal and checksum/version compatibility with existing
  `flyway_schema_history`. Mitigation: keep file names and contents byte-identical; verify
  against a snapshot of a migrated database.
- *JSP removal* — `index.jsp` creates the session the SPA expects; a naive static
  `index.html` can change SSO bootstrap behaviour. Mitigation: explicit OIDC JS/config
  handling and E2E SSO test.
- *Vendor dead code* — `weblogic.application.*` stubs, `NonCatalogLogger`, and
  `InventoryNotificationMDB`'s `t3://localhost:7001` JNDI client are unreachable on the target;
  leaving them compiles but implies broken features. Mitigation: delete in Phase 2 with a
  documented decision, or reimplement inventory notification as a proper consumer if the
  capability is required.
- *Configuration drift* — `keycloak.json` hardcodes `http://localhost:8081`, README hardcodes
  `postgresPW`, `realm-export.json` contains client secrets, and `coolstore.json` hardcodes
  `SSO_ENABLED`/`API_ENDPOINT`. Mitigation: externalize all of it into
  ConfigMap/Secret and environment variables; scan images/manifests for secrets.
- *Front-end EOL dependencies* — AngularJS 1.x and its bower tree are unmaintained and may
  carry known CVEs; image/dependency scanning may flag them. Mitigation: document as accepted
  risk with a follow-up modernization epic; do not block the backend migration on it.

## Assumptions Requiring Confirmation

This prompt was produced in an unattended planning run, so the requirement-gathering questions
were answered from repository evidence rather than from a conversation. Confirm these before
execution starts:

| Assumption | Evidence | Impact if wrong |
|---|---|---|
| Target is **Quarkus 3.x / Java 21**, not Spring Boot or a plain Jakarta EE 10 runtime on JBoss | `.konveyor/profiles/quarkus-profile/profile.yaml` (`target=quarkus\|quarkus3\|jakarta-ee`); `rules/rule.yaml` labels `target=quarkus3`, `source=openjdk8`; audit library 2.0.0 requires Java 21 | Groups 2–8 change substantially |
| Target platform is **OpenShift** | `pom.xml` TODO "Add OpenShift profile"; containerization/deployment requirements mandated by the migration programme | Manifests and pipeline change |
| Migration is an **in-place layered modernization**, not a microservice decomposition | 1.8 K LOC, 4 packages, `ShoppingCart` 36-call hub, remote EJB call inside pricing | If services are required, add a decomposition phase |
| **PostgreSQL schema and Flyway history are preserved** (no DB replacement) | `V1_1`/`V1_2` migrations, `CoolstoreDS` datasource | DDL/checksum work in Group 3 changes |
| **JMS `topic/orders` semantics must be preserved** with a real broker (not replaced by an in-process queue) | README's two-node competing-consumer demo; `OrderServiceMDB` + `ShoppingCartOrderProcessor` | Group 5 simplifies or gets harder |
| Audit events go to a **TCP collector** in all environments | `StreamableAuditLogger` in 2.0.0 (`audit.stream.host/port`, default `localhost:9000`) | Need a collector or a no-op profile |
| Security posture is unchanged except for removing hardcoded secrets; **protecting `/services/**` with OIDC is a change requiring sign-off** | `keycloak.json` is SPA-only today; no security constraints in `web.xml` | Group 6 scope changes |
| **AngularJS 1.x stays** (no front-end rewrite) | `src/main/webapp/app` + `bower_components` | A front-end epic must be added |
| No hard deadline; a few iterations are acceptable | No timeline artefacts in the repo | Reprioritize Groups 7–9 |
| Single small team, new to Quarkus | Repo size, demo-style codebase | Training/enablement effort changes |

---

**Request**: Create a comprehensive migration plan following the above requirements. The plan
should include specification, design, task breakdown, and user stories suitable for immediate
execution by the development team. Tasks must be dependency-ordered so that each one leaves
the build compiling and testable, starting with characterization tests against the current
JBoss EAP deployment and ending with a containerized, OpenShift-deployed Quarkus 3 / Java 21
application with behaviour verified as unchanged.
