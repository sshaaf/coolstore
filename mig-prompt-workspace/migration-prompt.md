# Migration Prompt

## Current Application Summary

**CoolStore** is a Java EE 7 storefront monolith (`com.redhat.coolstore:monolith:1.0.0-SNAPSHOT`, packaging `war`,
final name `ROOT.war`) deployed on **JBoss EAP 7.4** in standalone-full-ha mode with a **PostgreSQL** datasource
(`java:jboss/datasources/CoolstoreDS`), an **ActiveMQ Artemis** JMS topic (`topic/orders`) and **Keycloak 20**
for OIDC/SSO. The WAR serves both the REST backend (`/services/*`) and a static **AngularJS 1.x** single-page
storefront. The graph index (rgctl, 47 first-party files indexed after excluding vendored `bower_components`)
reports 238 functions, 133 classes, 5 interfaces, 45 modules, 152 call edges and 196 use edges — a small,
tightly-coupled layered monolith, not a distributed system.

The backend is organised in five layers: `rest` (JAX-RS resources), `service` (EJB + CDI business services and
two JMS message-driven beans), `model` (JPA entities plus DTOs), `persistence` (an `EntityManager` CDI producer),
and `utils` (JSON transformers, Flyway bootstrap, `/health.jsp`, and a **WebLogic compatibility shim package**
`weblogic.application` / `weblogic.i18n.logging` that is checked into `src/main/java`). The cart is held in a
`@Stateful` EJB bound to a `@SessionScoped` JAX-RS resource, checkout is published asynchronously to a JMS topic,
and an MDB persists orders and decrements inventory. No test sources exist (`maven.test.skip=true`).

The code carries several vendor/lock-in signals: hard-coded WebLogic JNDI lookups
(`weblogic.jndi.WLInitialContextFactory`, `t3://localhost:7001`, `PortableRemoteObject.narrow`) in
`InventoryNotificationMDB`, a remote-EJB JNDI lookup
(`ejb:/ROOT/ShippingService!…`, `org.wildfly.naming.client.WildFlyInitialContextFactory`) inside
`ShoppingCartService`, JBoss-specific resource names (`java:/topic/orders`,
`java:jboss/datasources/CoolstoreDS`), and a `system`-scoped local JAR `com.enterprise:audit-logging-library:1.0.0`
consumed through `systemPath` (`lib/audit-logging-library-1.0.0.jar`). A local Konveyor ruleset
(`rules/ruleset.yaml`, 5 mandatory rules, target `quarkus3`) evaluates to **1 violation**
(`audit-logging-0003`, `FileSystemAuditLogger` instantiation in `OrderService.java:5`) and the repository ships
`.konveyor/profiles/quarkus-profile/profile.yaml` selecting targets `quarkus`, `quarkus3`, `jakarta-ee`.

**Technology Stack:**
- Java 8 source/target (`maven-compiler-plugin` 3.0), Java EE 7 (`javax.javaee-api:7.0` provided)
- EJB 3.2 (`@Stateless`, `@Stateful`, `@Singleton`, `@MessageDriven`, `@Remote`), CDI 1.1
- JPA 2.1 with Hibernate provider, `persistence.xml` 2.1 JTA datasource
- JAX-RS 2.0 (`javax.ws.rs`), JMS 2.0 (`javax.jms`), `javax.json` (JSON-P 1.0)
- Flyway 4.1.2 (programmatic API `new Flyway()` / `setDataSource()`)
- `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`, `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
- Custom `com.enterprise:audit-logging-library:1.0.0` (system scope, local JAR); **2.0.0 JAR is already vendored**
  in `lib/` and is a Java 16+ record-based API (`AuditEvent` record, `StreamableAuditLogger`, `logEventAsync`)
- AngularJS 1.x + PatternFly + Keycloak JS adapter, vendored under `src/main/webapp/bower_components`
- Maven WAR build, no tests, no container assets, no Kubernetes manifests

**Architecture:**
- Layered monolith: `rest` → `service` → (`model`, `utils`) with `persistence` providing `EntityManager`
- Metagraph edges: `rest→service` (14 calls), `rest→model` (17 calls), `service→model` (37 calls),
  `service→utils` (5), `utils→model` (23), `persistence→root` (4 uses) — a classic acyclic layered dependency
- Integration points: PostgreSQL (JPA + Flyway migrations in `src/main/resources/db/migration`), JMS topic
  `topic/orders` (producer `ShoppingCartOrderProcessor`, consumers `OrderServiceMDB` + `InventoryNotificationMDB`),
  Keycloak OIDC (frontend `keycloak.json`, backend relies on container-managed security only in web.xml)
- Frontend is a static AngularJS SPA served from the same WAR; `coolstore.json` carries a `MONOLITH` flag and
  `API_ENDPOINT`/`SECURE_API_ENDPOINT` keys, so the SPA can already be pointed at split services
- 9 circular dependencies and 812 micro-communities in the graph (modularity 0.38) indicate tangled
  intra-package detail rather than clear module boundaries; package-level grouping is the reliable unit of work

**Scale:**
- 30 first-party Java classes, 1,785 lines of Java; 463 lines of first-party JavaScript (app/ only)
- 8 REST endpoint methods across 3 resources (`/services/products`, `/services/orders`, `/services/cart`)
- 4 JPA entities/tables (`PRODUCT_CATALOG`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`)
- 2 JMS consumers, 1 JMS producer, 1 scheduled/startup Flyway migrator, 1 remote EJB interface
- Migration plan export (`hybrid_default`, `scheduled`): 12 package-level steps; highest priority-rank packages
  are `rest` (rank 1), `weblogic.i18n.logging` (2), `utils` (3), `main.webapp.app` (4), `service` (5);
  `model` (7) and `persistence` (10) schedule last because everything depends on them

**Requirements captured for this engagement (defaults derived from repository evidence):**
1. **Source**: Java EE 7 monolith on JBoss EAP 7.4 (confirmed by `pom.xml`, `persistence.xml`, README, `.konveyor` profile)
2. **Target**: **Quarkus 3.x on Java 21** with **Jakarta EE 10** APIs — derived from `rules/*.yaml`
   (`konveyor.io/target=quarkus3`, "update java version to 21"), `.konveyor/profiles/quarkus-profile/profile.yaml`,
   and the presence of `lib/audit-logging-library-2.0.0.jar` (Java 21 record API). No servlet/EJB container retained.
3. **Approach**: **Phased, in-place modernization** of the single deployable into one Quarkus service
   (not a microservice split) — the application is only ~1.8 KLOC and its REST surface is small, so a
   big-bang rewrite is not justified; phases keep the app buildable and deployable at every step.
4. **Deployment platform**: **Red Hat OpenShift** (latest stable 4.x), images built with Podman/Buildah,
   pipelines on Tekton (platform-managed; this repo must not carry CI workflow definitions).
5. **Timeline / team / risk**: not stated by the requester. Working assumption: 2–3 Java developers over
   ~6 weeks, no hard deadline, acceptable planned downtime for cutover (dev/demo system), parallel run not required.
   These are assumptions to confirm with the product owner; they do not change the task decomposition.

**Open questions (non-blocking, assume defaults above):**
- Is the AngularJS storefront in scope, or should it stay untouched and be served as static content?
- Must the existing PostgreSQL schema/data be preserved as-is, or may Flyway baselines be regenerated?
- Is JMS retained (Artemis/AMQP) or replaced with Kafka or an in-process CDI event for this demo scope?
- Is SSO mandatory for the migrated service, or can it remain frontend-only for the demo?

## Target Platform and Technologies

**Target Stack:**
- **Quarkus 3.x** (latest stable 3.x LTS line), **Java 21**, **Jakarta EE 10** (`jakarta.*` namespace)
- `quarkus-rest` / `quarkus-rest-jackson` (Jakarta REST) replacing JAX-RS + `javax.json` manual marshalling
- `quarkus-hibernate-orm` + `quarkus-jdbc-postgresql` replacing JPA 2.1 + JBoss datasource
- `quarkus-flyway` (Flyway 10.x) replacing programmatic Flyway 4.1.2 bootstrap in an EJB `@Singleton`
- CDI (`quarkus-arc`) replacing EJB: `@Stateless` → `@ApplicationScoped`, `@Stateful` cart → session-scoped
  service backed by a shared store or sticky-session strategy, `@MessageDriven` → `@Incoming` reactive messaging
- `quarkus-messaging` (SmallRye Reactive Messaging) with an Artemis/AMQP or Kafka connector replacing the
  JMS `topic/orders` producer/consumers
- `quarkus-oidc` (or frontend-only Keycloak JS) replacing container-managed security / Keycloak adapter
- `quarkus-smallrye-health` replacing `/health.jsp`
- **`audit-logging-library` 2.0.0** (vendored in `lib/`) replacing 1.0.0: `StreamableAuditLogger` over TCP,
  `AuditEvent` record instantiation, `logEventAsync`
- Maven build producing a **runnable fast-jar** and a container image (no WAR, no `systemPath` dependencies)
- **Migration rationale:** the target is mandated by the repository's own Konveyor profile and ruleset, matches
  the upstream CoolStore modernization story, gives a small footprint suitable for OpenShift, and removes the
  EJB/WebLogic/JBoss lock-in that currently blocks containerization.

**Deployment Platform:**
- Red Hat OpenShift 4.x (Kubernetes-based container platform)
- Images built with Podman/Buildah from a multi-stage `Containerfile` (UBI 9 + OpenJDK 21 runtime base)
- Deployment strategy: rolling update; `Recreate` acceptable for the demo profile
- CI/CD: OpenShift Pipelines (Tekton) managed by the migration platform — **do not add `.github/workflows` files**

**Infrastructure Requirements:**
- Container runtime: Podman (rootless) for local build/test; Docker-compatible
- Orchestration: Kubernetes on OpenShift (Deployment, Service, Route, ConfigMap, Secret)
- External services: PostgreSQL 15+ (in-cluster or operator-provisioned), a JMS/AMQP broker (Artemis or
  Kafka via Strimzi/AMQ Streams) if asynchronous checkout is retained, Keycloak (existing realm `eap`)
- Configuration externalised via MicroProfile Config (`quarkus.datasource.*`, broker URL, OIDC issuer URL)
- Health: `/q/health/live`, `/q/health/ready`; metrics via `/q/metrics` (Micrometer/Prometheus) as a target

## Migration Approach

**Strategy:** Phased, dependency-ordered in-place modernization (hybrid: build-first foundation, then layer-by-layer
conversion, deployment cutover at the end). Not a strangler-fig service extraction.

**Reasoning:**
- The application is small (30 classes) but spans nearly every legacy Java EE API (EJB, JMS, JPA, JAX-RS,
  JSON-P, JNDI, remote EJB, Flyway-as-EJB, vendor shims), so each layer must be converted deliberately.
- rgctl's scheduled migration plan puts leaf/legacy-specific packages first (`weblogic.*` shims, static
  frontend) and shared core last (`model`, `service`) because everything depends on the core; the plan below
  follows the same dependency direction but front-loads build/toolchain work that would otherwise block
  compilation of every later step.
- EJB container services (transactions, `@MessageDriven` lifecycle, remote EJB) have no direct Quarkus
  equivalent, so the messaging and cart/session designs must be decided before the services are ported.
- Big-bang rewrite was rejected: it would discard working, verifiable behaviour (pricing, promotion,
  shipping calculation) that characterization tests can lock down cheaply.

**Key Considerations:**
- `javax.*` → `jakarta.*` namespace change touches 100% of imports (13 `javax.ejb`, 30 `javax.persistence`,
  17 `javax.ws`, 12 `javax.inject`, 9 `javax.enterprise`, 7 `javax.jms`, 6 `javax.json`, 6 `javax.naming`)
- EJB semantics: `@Stateless`→`@ApplicationScoped`; `@Singleton @Startup` Flyway migrator→`quarkus-flyway`
  (`migrate-at-start=true`) or an `@Startup` CDI bean; `@Stateful` cart holding per-client state→
  `@SessionScoped`/Redis-backed cart service (session affinity or shared state must be chosen explicitly)
- Remote EJB: `ShippingServiceRemote` + `InitialContext` lookup of `ejb:/ROOT/ShippingService!…` must become a
  direct CDI injection or an outbound REST/gRPC call if the shipping service stays separate
- WebLogic JNDI: `InventoryNotificationMDB` (which also has no `@MessageDriven` annotation — dead/duplicate path)
  and `StartupListener` extend WebLogic API; both shim packages should be deleted, not ported
- JMS: `JMSContext` injection + `@Resource(lookup="java:/topic/orders")`, `destinationLookup`,
  `AcknowledgeMode` activation config → SmallRye Reactive Messaging `@Channel`/`@Incoming`, or an in-process
  CDI event if the demo no longer needs a broker (decision required, default: keep async via broker)
- JSON: `javax.json` builders/readers in `Transformers` → Jackson (`ObjectMapper`) or Jakarta JSON-P;
  wire format (`productSku`, `orderValue`, `items[]`) must remain byte-compatible with existing clients/tests
- JPA: `@GeneratedValue` without a strategy, `@OneToOne @PrimaryKeyJoinColumn`, `@OneToMany @JoinColumn`,
  `columnDefinition = "text"` — must be validated against Hibernate ORM 6 defaults; `hibernate.jdbc.use_get_generated_keys=false`
  must not be carried over blindly
- Flyway 4.1.2 → 10.x breaking API (`new Flyway()` removed) and PostgreSQL 15 dialect/checksum behaviour
- `lib/audit-logging-library-1.0.0.jar` is a `system`-scope dependency with `systemPath`, which Quarkus/GraalVM
  builds do not support; it must become a normal Maven dependency (multi-module build or `mvn install:install-file`
  / local repository) and be upgraded to 2.0.0 (Java 21 records; adds a Jackson requirement)
- Vendored `bower_components` (AngularJS 1.x, PatternFly, jQuery) is EOL front-end tech; the migration keeps it
  as static content under `src/main/resources/META-INF/resources` for scope control, but it must not be indexed
  or rewritten as part of this effort (rgctl exclude pattern `src/main/webapp/bower_components`)
- `maven.test.skip=true` and zero test sources: characterization tests must be authored before behaviour changes
- Config/secrets: `realm-export.json` (realm bootstrap), `keycloak.json` (`http://localhost:8081`), README DB
  password, and `replication password` in the JBoss CLI instructions are dev credentials; `--with-security`
  flagged 2 potential secrets — container/deployment work must externalise them into OpenShift Secrets

## Required Deliverables

### 1. Containerization
- A single application image built from a multi-stage `Containerfile` (UBI 9 + OpenJDK 21 builder → UBI 9
  OpenJDK 21 runtime), producing a Quarkus fast-jar; `quarkus.container-image` (Jib) as an alternative
- No `root` user at runtime, explicit `USER 1001` (OpenShift arbitrary UID compatible), read-only-friendly
  filesystem (audit logs and temp files must go to an emptyDir/volume, not the image layer)
- Multi-arch consideration (x86_64, aarch64) and deterministic image tags
- Image vulnerability scanning (Trivy/Grype or OpenShift ACS) in the pipeline, with a documented threshold
- Registry strategy: OpenShift internal registry (`image-registry.openshift-image-registry.svc:5000`) with a
  `BuildConfig`/Tekton `Pipeline` reference; no credentials in the repo
- `.dockerignore`/`.containerignore` excluding `target/`, `.git/`, `bower_components` sources not needed at runtime

### 2. OpenShift Deployment
- Kubernetes/OpenShift manifests (or Helm/Kustomize overlay): `Deployment`, `Service`, `Route` (edge TLS),
  `ConfigMap`, `Secret`, `ServiceAccount`, optional `HorizontalPodAutoscaler`, `PodDisruptionBudget`
- Probes wired to `/q/health/live` and `/q/health/ready`; startup probe tuned for Quarkus boot
- Resource `requests`/`limits` sized from a measured baseline (start: 256Mi/100m request, 512Mi/500m limit)
- Externalised configuration: datasource URL/credentials, broker URL/topic, OIDC issuer, audit stream host/port
- Secrets for PostgreSQL and Keycloak client credentials created as OpenShift `Secret` objects (never in git)
- Rollback: `oc rollout undo`, image tag pinning, and a documented database rollback (Flyway is forward-only →
  snapshot/restore procedure required)
- Observability: structured JSON logs to stdout, `/q/metrics` scraped by the platform, basic alert on 5xx rate

### 3. Test Coverage
- **Characterization tests first** (before any behaviour change), covering:
  - Pricing/promotion math: `PromoService.applyCartItemPromotions`/`applyShippingPromotions`,
    `ShoppingCartService.priceShoppingCart`, `ShippingService.calculateShipping`/`calculateShippingInsurance`
    (all boundary values: 25, 50, 75, 100, 500, 10000)
  - Cart lifecycle: add/remove/set/dedupe/checkout via `/services/cart/*` including the `dedupeCartItems` merge
  - Wire contracts: JSON produced by `Transformers.shoppingCartToJson` and consumed by `jsonToOrder`
    (`productSku`, `quantity`, `orderValue`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items`)
  - REST contracts: `/services/products`, `/services/products/{itemId}`, `/services/orders`,
    `/services/orders/{orderId}` including 200/404 shapes
  - DB layer: JPA mappings round-trip against PostgreSQL; Flyway migrations `V1_1`, `V1_2` apply cleanly
- **Unit tests**: minimum 70% line coverage on `service`, `utils`, `rest` packages (target `rest` ≥ 80%)
- **Integration tests**: `@QuarkusTest` with Testcontainers (PostgreSQL, broker), covering the checkout →
  message → order-persist → inventory-decrement path; Flyway migration test on a fresh schema
- **End-to-end tests**: the storefront user journey (browse → add to cart → checkout) against the OpenShift
  route, plus OIDC login when SSO is enabled
- **Performance tests**: baseline the monolith (`GET /services/products`, `POST /services/cart/.../checkout`)
  today, then assert the Quarkus service is within ±10% p95 and document cold-start/boot time
- Test execution in the build (`maven.test.skip` removed), reports published by the Tekton pipeline

### 4. Detailed Migration Plan
- Phase-by-phase breakdown with explicit entry/exit criteria per phase
- Task lists with dependencies, owners (role-level), and effort estimates
- Risk register with likelihood/impact and concrete mitigations for each legacy API cluster
- Rollback procedure per phase (git revert points, image tag rollback, DB restore)
- Monitoring/observability setup and post-cutover verification checklist
- Documentation updates (README replaces JBoss/Keycloak runbook with Quarkus/OpenShift runbook)
- Team enablement: short enablement on Quarkus CDI, Reactive Messaging and EJB→CDI mapping

## Success Criteria

- `mvn clean verify` builds a Quarkus fast-jar on Java 21 with **tests enabled** and zero `javax.*` imports
- All 8 REST endpoints behave identically (status, payload shape, pricing results) versus the captured baseline
- Checkout still publishes an order event asynchronously, and orders/inventory are persisted in PostgreSQL
- No references remain to WebLogic (`weblogic.*` packages deleted), JBoss JNDI names (`java:jboss/...`,
  `java:/topic/orders`), remote EJB lookups, or `systemPath` dependencies
- `audit-logging-library` upgraded to 2.0.0 and used via `StreamableAuditLogger` + `AuditEvent` record +
  `logEventAsync` (Konveyor violation `audit-logging-0003` closed; rules 0001/0002/0004/0005 verified clean)
- Container image builds reproducibly, runs as non-root on OpenShift, and passes image scanning policy
- Application is reachable over an OpenShift `Route`, health probes green, config/secrets externalised
- Characterization + unit + integration + E2E suites green in the pipeline; coverage targets met
- `mig-plan-workspace/tasks.md` and `UserStory.md` fully executed with each task's acceptance criteria met
- Rollback procedure demonstrated once (image rollback + DB restore rehearsal)

## Constraints and Risks

**Constraints:**
- Only Java 21 + Quarkus 3.x + Jakarta EE 10 are available as target; the `javax` namespace is not permitted
- Must remain a **single deployable** for this migration (no service extraction beyond keeping the existing
  `ShippingService` boundary abstract)
- No changes under `.github/workflows/` (platform-managed Tekton); no new CI workflow files
- Worktree must stay clean: build output in `target/`, ephemeral scripts/notes outside the repo
- The `lib/*.jar` audit library must be consumed through a real Maven dependency mechanism, never `systemPath`
- PostgreSQL schema and seed data (`V1_1`, `V1_2`) must remain the source of truth for the migrated service

**Risks:**
- **High — EJB `@Stateful` cart semantics**: the current cart is per-bean-instance memory shared through a
  `@SessionScoped` resource; a naive `@ApplicationScoped` port silently corrupts carts across users.
  *Mitigation*: explicit cart repository (in-memory keyed by `cartId` for demo, Redis for realism) with
  characterization tests covering concurrent carts.
- **High — remote EJB + WebLogic JNDI removal**: `ShoppingCartService.lookupShippingServiceRemote()` and
  `InventoryNotificationMDB` depend on vendor naming; deleting them changes checkout behaviour.
  *Mitigation*: replace with direct CDI injection first (behaviour-preserving), then decide on out-of-process
  shipping if needed; delete `weblogic.*` shims and `StartupListener` in a dedicated step with tests proving
  no functional regression.
- **Medium — messaging semantics**: JMS topic fan-out (2 consumers) → Reactive Messaging connector may
  change delivery guarantees/ordering. *Mitigation*: keep topic semantics, document at-least-once handling,
  and add an integration test asserting both consumers receive the same order event.
- **Medium — Flyway 4 → 10 + Hibernate 6 schema validation**: DDL/sequence behaviour (`@GeneratedValue`
  default strategy) may differ. *Mitigation*: baseline-on-migrate with validation tests against a fresh
  PostgreSQL, and an explicit `GenerationType.IDENTITY`/sequence decision per entity.
- **Medium — audit library 2.0.0**: record-based API, Jackson dependency, CPU-heavy TCP streaming logger;
  `OrderService` currently never emits an event, so "migration" means both upgrade and actual wiring.
  *Mitigation*: install 2.0.0 as a normal dependency, add `logEventAsync` calls for order save, and test
  against a stub TCP endpoint; keep the old `./device-inventory-audit-logs` path only as a fallback profile.
- **Medium — frontend/AngularJS**: EOL client stack served by the same WAR; moving static resources can break
  `/app/*`, `/bower_components/*`, `/partials/*` paths. *Mitigation*: serve from
  `META-INF/resources` preserving paths, verify with E2E test; frontend rewrite explicitly out of scope.
- **Low — SSO/Keycloak**: `coolstore.json` has `SSO_ENABLED=true` pointing at `localhost:8081`.
  *Mitigation*: parameterise issuer URL via ConfigMap, keep frontend adapter, add backend `quarkus-oidc`
  only if backend authorization is required.
- **Low — small graph signal**: package metagraph is sparse (152 call edges) because CDI injection and JMS
  dispatch are not call edges; blast-radius scores are all 0. *Mitigation*: rely on package dependency
  ordering + explicit source review, and re-run `rgctl discover` after each phase to confirm no new cycles.

---

**Request**: Create a comprehensive migration plan following the above requirements. The plan should include specification, design, task breakdown, and user stories suitable for immediate execution by the development team.
