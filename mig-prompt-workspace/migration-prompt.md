# Migration Prompt

## Current Application Summary

CoolStore is a Java EE 7 monolith (`com.redhat.coolstore:monolith:1.0.0-SNAPSHOT`, packaged as `ROOT.war`) that
implements a small e-commerce storefront: an AngularJS single-page application talks to a JAX-RS API under
`/services`, business logic lives in EJB 3.2 beans, JPA 2.1/Hibernate entities persist the catalog, inventory and
orders to PostgreSQL, and orders are published as JSON messages to the JMS topic `topic/orders`, where two
message-driven beans consume them (one persists the order and decrements stock, one raises low-stock notifications).
The WAR is deployed to JBoss EAP 7.4 (cluster of two nodes) with an ActiveMQ Artemis topic, a Keycloak 20 realm for
SSO, and a Flyway-managed schema.

The codebase is small but exercises a wide slice of the Java EE platform: CDI producers, `@Stateless`/`@Stateful`/
`@Singleton`/`@Startup` beans, `@Remote` EJB remoting over WildFly JNDI, `@MessageDriven` JMS listeners, container-managed
`EntityManager` from a JNDI datasource, JSON-P serialization, and two legacy vendor surfaces: `weblogic.*`
compatibility shims and a system-scoped `audit-logging-library` 1.0.0 jar.

**Technology Stack:**
- Java 8 source/target (JDK 17 runtime per README), Java EE 7 (`javax.*`: EJB 3.2, JPA 2.1, JAX-RS 2.0, CDI 1.1, JMS 2.0, JSON-P 1.0, JAXB)
- JBoss EAP 7.4 application server, WAR packaging, clustering via `standalone-full-ha.xml`
- PostgreSQL (`java:jboss/datasources/CoolstoreDS`), Flyway 4.1.2 (`src/main/resources/db/migration`)
- ActiveMQ Artemis JMS topic `topic/orders`, two MDB consumers
- Keycloak 20.0.5 (realm export checked in as `realm-export.json`), AngularJS 1.x + PatternFly front end
- `com.enterprise:audit-logging-library:1.0.0` (system scope, `lib/`), WebLogic 12 shims

**Architecture:**
- Monolithic 3-tier: AngularJS SPA → JAX-RS resources (`/services/{products,cart,orders}`) → EJB services → JPA → PostgreSQL
- Asynchronous order pipeline: `ShoppingCartOrderProcessor` publishes to `topic/orders`; `OrderServiceMDB` persists +
  updates inventory; `InventoryNotificationMDB` reports stock below a threshold of 50
- Per-user shopping cart state held in a `@Stateful` `ShoppingCartService` injected into a `@SessionScoped` JAX-RS resource
- Cross-cutting: CDI `Logger` producer, Flyway migration on `@Startup`

**Scale:**
- 30 Java files (27 application classes + 3 WebLogic shims), 238 functions, 133 classes, 5 interfaces
- 971 graph nodes / 2,544 edges in application scope (rgctl, vendored assets excluded), 9 circular dependencies
- REST surface: 8 endpoints across 3 resources; 4 JPA entities; 9 EJB beans; 2 JMS producers/listeners pairs
- Front end: 5 AngularJS modules + vendored bower assets (unchanged by this migration)

## Target Platform and Technologies

**Target Stack:**
- **Quarkus 3.20.x** (Jakarta EE 10 / MicroProfile), **Java 21**
- Quarkus extensions: `quarkus-resteasy-jackson` (JAX-RS with Jackson), `quarkus-hibernate-orm` (JPA),
  `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-health`, `quarkus-smallrye-openapi`,
  `quarkus-arc` (CDI), `quarkus-smallrye-reactive-messaging-kafka` (replaces JMS), `quarkus-jackson`
- `audit-logging-library:2.0.0` (vendored `lib/audit-logging-library-2.0.0.jar`), Java 21 records API,
  `StreamableAuditLogger` over TCP, non-blocking `logEventAsync`
- **Migration rationale:** the repository itself ships a Konveyor profile (`.konveyor/profiles/quarkus-profile/profile.yaml`)
  selecting `konveyor.io/target=quarkus|quarkus3|jakarta-ee` rules, and `rules/rule.yaml` labels every mandatory rule
  `konveyor.io/target=quarkus3` while requiring "upgrade audit-logging-library to 2.0.0, update java version to 21".
  Quarkus removes the application server, gives fast startup and low memory footprint, and is the supported
  modernization path for this Red Hat demo application.

**Deployment Platform:**
- Red Hat OpenShift 4.x (Kubernetes), internal registry image streams
- Health probes on `/q/health/live` and `/q/health/ready`, metrics on `/q/metrics`

**Infrastructure Requirements:**
- Container runtime: Podman/Docker with multi-stage builds
- Base images: `registry.access.redhat.com/ubi9/openjdk-21` (build) and
  `registry.access.redhat.com/ubi9/openjdk-21-runtime` (runtime), non-root UID 185
- PostgreSQL 15 (or the existing instance) — same schema, migrated by Flyway
- Kafka (AMQ Streams / Strimzi) topic `orders` replacing the JMS topic `topic/orders`
- Keycloak remains the OIDC provider for the SPA; the Quarkus app does not enforce auth on `/services` (same as today)
- Config via ConfigMaps/Secrets: datasource URL/user/password, audit stream host/port

## Migration Approach

**Strategy:** Big-bang in-place rewrite of the monolith (single deployable), executed in dependency-ordered waves

**Reasoning:** the application is small (≈238 functions), has no external consumers other than its own SPA, and the
target runtime replaces the container (EAP) wholesale — a strangler-fig split would add distributed-systems cost with
no risk reduction. Risk is managed instead by migrating in waves (platform/foundation → model/persistence →
services/messaging → REST/static → audit library → tests → container → deployment) with the HTTP contract held fixed.

**Key Considerations:**
- **API contract is frozen:** every path under `/services` keeps its shape, verbs, and JSON field names so the
  unchanged AngularJS SPA continues to work. `/services/products[/{itemId}]`, `/services/cart/{cartId}[/{itemId}/{quantity}]`,
  `/services/cart/{cartId}/{tmpId}`, `/services/cart/checkout/{cartId}`, `/services/orders[/{orderId}]`.
- **Stateful EJB → explicit cart store:** Quarkus CDI has no `@SessionScoped` per-request instance model for JAX-RS
  resources; `ShoppingCartService` becomes an application-scoped service backed by a concurrent store keyed by `cartId`.
  Behaviour (one cart per `cartId`, transfer between carts, reset on checkout) is preserved.
- **EJB semantics:** `@Stateless`/`@Singleton` → `@ApplicationScoped` CDI beans (Quarkus has no EJB container);
  container-managed transactions become `@Transactional`; `@Remote` remoting and the WildFly JNDI lookup disappear
  (plain injection); `@MessageDriven` → Reactive Messaging `@Incoming` methods.
- **JMS topic semantics:** a topic delivers each order to *both* consumers. With Kafka this requires two distinct
  channels/consumer groups over the same `orders` topic (order persistence and inventory notification).
- **Persistence:** keep the existing JPA entities and SQL schema; replace the JNDI datasource with
  `quarkus.datasource.*` and `quarkus.hibernate-orm.*`, run Flyway at start-up with baseline-on-migrate.
- **Audit logging (mandatory Konveyor rules):** upgrade to 2.0.0, replace `new FileSystemAuditLogger(config)` with
  `new StreamableAuditLogger(config)` configured for TCP (`streamHost`/`streamPort`), replace synchronous
  `logEvent`/`logSuccess`/`logFailure` usage with `logEventAsync(new AuditEvent(...))` and the 14-component record.
- **Static content & JSP:** Quarkus does not serve JSP; `index.jsp` (which only opens a session) becomes
  `src/main/resources/META-INF/resources/index.html`; `health.jsp` is replaced by SmallRye Health. Bower assets and the
  AngularJS app move verbatim so the UI is unchanged.
- **Delete dead vendor code:** `weblogic.*` shims and `StartupListener` have no replacement; `Java EE` `beans.xml`/
  `web.xml`/`persistence.xml` are no longer required.

## Required Deliverables

### 1. Containerization
- Multi-stage `Containerfile`/`Dockerfile`: UBI 9 + OpenJDK 21 builder running `mvn package`, UBI 9 OpenJDK 21 runtime
  running the Quarkus fast-jar as non-root
- Layer caching (dependency resolution before source copy), `JAVA_OPTS`/`JAVA_MAX_MEM_RATIO`-friendly entrypoint
- `.dockerignore` to keep vendored/binary assets and build output out of the build context
- Image scanning guidance (OpenShift image scanning / `podman scan`), no secrets baked into layers

### 2. OpenShift Deployment
- `Deployment` with readiness/liveness probes against `/q/health`, resource requests/limits, non-root security context
- `Service` + `Route` (edge TLS termination)
- `ConfigMap` for non-secret configuration and `Secret` for datasource credentials and audit stream settings
- PostgreSQL `Deployment`/`Service`/`PersistentVolumeClaim` (dev/test) and Kafka via AMQ Streams `KafkaTopic`
- `kustomization.yaml` so the manifests apply as one unit; documented `oc apply -k` workflow

### 3. Test Coverage
- **Characterization tests** first, pinning the current HTTP contract and pricing/messaging behaviour:
  product listing/lookup, cart add/remove/dedupe, promotion (25% off item `329299`, free shipping ≥ 75),
  shipping bands (2.99/4.99/6.99/8.99/10.99) and insurance (2%/1.5%/1%), checkout publishing an order,
  order persistence + inventory decrement, and the JSON order payload shape.
- **Unit tests** for `ShippingService`, `PromoService`, `Transformers`, `ShoppingCart` (target ≥ 70% on migrated logic)
- **Integration tests** with `@QuarkusTest` + RestAssured + H2/Dev Services PostgreSQL for the REST + JPA + Kafka paths
- End-to-end/performance testing remains a follow-up in a cluster environment (no Maven/JDK in this harness)

### 4. Detailed Migration Plan
- Phase-by-phase breakdown with dependencies, risk assessment, rollback (redeploy the previous WAR/EAP image),
  observability (`/q/health`, `/q/metrics`, structured logs), and documentation updates (README for the Quarkus build,
  run, and deployment flow)

## Success Criteria

- Quarkus application builds from the repository root and serves every `/services` endpoint with unchanged JSON
- AngularJS SPA loads unmodified from `/` and completes add-to-cart → checkout against the Quarkus backend
- Order flow publishes to the `orders` Kafka topic and is consumed by both handlers (persist + inventory notification)
- Flyway migrates a fresh PostgreSQL database on start-up; schema matches `V1_1__CreateSchema.sql`
- Audit logging uses `audit-logging-library:2.0.0` (`StreamableAuditLogger` + `logEventAsync` + `AuditEvent` record) on Java 21
- No `javax.*` enterprise imports, no EJB/JNDI/WebLogic references remain
- Characterization + unit tests pass; container image builds on UBI 9 and runs as non-root
- OpenShift manifests apply cleanly and the pod reaches `Ready` with healthy probes

## Constraints and Risks

**Constraints:**
- Front end must not change (AngularJS + bower assets are out of scope beyond their relocation)
- Same PostgreSQL schema and seed data; no data model redesign
- No Maven/JDK toolchain and no cluster in the execution harness — build/deploy verification is limited to source-level
  validation plus static review, and must be re-run by the team with `mvn verify` and `oc apply -k`
- Kafka is introduced where JMS existed; the broker is an infrastructure dependency that must be provisioned

**Risks:**
- **Cart state semantics** (highest): `@Stateful`/`@SessionScoped` behaviour cannot be reproduced literally in Quarkus;
  mitigated by an explicit `cartId`-keyed store, a documented behaviour change (carts are now shared across replicas
  rather than node-local), and tests for add/transfer/checkout.
- **Messaging semantics**: JMS topic fan-out → Kafka consumer groups; a single group would silently drop one consumer's
  work. Mitigated by two channels with distinct group ids and a test asserting both handlers run.
- **Audit library 2.0.0** requires Java 21 and Jackson on the classpath; the TCP stream target may be unavailable at
  runtime — `logEventAsyncSafe` semantics and non-blocking calls prevent request-path failures.
- **Legacy URL details**: `/services` prefix, trailing-slash variants (`/services/orders/`), and JSON field names
  (`shoppingCartItemList`, `cartItemTotal`, …) are easy to break with automatic refactors; pinned by tests.
- **Keycloak/SSO**: unchanged front-end adapter config must keep resolving `keycloak.json` from the app root.

---

**Request**: Create a comprehensive migration plan following the above requirements. The plan should include
specification, design, task breakdown, and user stories suitable for immediate execution by the development team.
