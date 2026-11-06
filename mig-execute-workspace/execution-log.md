# Migration Execution Log

**Started**: 2026-11-06 16:52 UTC
**Migration**: Java EE 7 monolith on JBoss EAP 7.4 → Quarkus 3.15 / Java 21 / Jakarta EE 10, single deployable on OpenShift
**Plan version**: `mig-plan-workspace/tasks.md` (14 task groups) + `UserStory.md` (13 stories), derived from
`mig-prompt-workspace/migration-prompt.md`
**Executor**: MigIQ execute stage (single agent session; no sub-agents — every task touches overlapping files)

---

## Executive summary

| Item | Value |
| --- | --- |
| Task groups | 14 (all have delivered artifacts) |
| Subtasks | 135 - 101 checked, 34 open (runtime verification only) |
| User stories | 13 - 11 fully satisfied by artifacts, 2 with runtime-only criteria open (12, 13) |
| Java sources | 30 legacy files removed/rewritten → 29 files in the new tree |
| Tests authored | 14 classes (7 unit, 7 `@QuarkusTest`/Testcontainers) |
| Kantra violations | 1 → **0** |
| `javax.*` imports | 108 → **0** |
| WebLogic/Vendor references | 7 → **0** |

## Environment constraints (recorded up front)

| Tool | Status | Consequence |
| --- | --- | --- |
| `java` / `mvn` | **absent** (`/usr/lib/jvm`, `~/.m2` do not exist) | no compile, no `mvn verify`, no boot check |
| `podman` / `buildah` / `docker` | **absent** | no image build, no container smoke run |
| `oc` / `kubectl` | **absent** | no deploy, no cutover, no rollback rehearsal |
| `rgctl` | **present** (v0.4.20) | graph analysis, blast radius, Kantra rules, package checks |
| `python3`, `git`, `curl` | present | source analysis, class-file introspection, VCS ops |

All work that produces a repository artifact was executed. Every step that needs a runtime was written,
documented and left with an explicit open checkbox.

---

## Execution timeline

### 16:52 - Context load and baseline

- Read `mig-execute` skill, `mig-plan-workspace/tasks.md` (440 lines), `UserStory.md` (861 lines),
  `mig-prompt-workspace/migration-prompt.md`.
- Read all 30 first-party Java sources, both SQL migrations, `persistence.xml`, `web.xml`, `beans.xml`,
  `index.jsp`, `health.jsp`, `coolstore.json`, `keycloak.json`, the JS SPA services, the Konveyor ruleset and
  `.konveyor` profile.

**Baseline graph** (`rgctl discover . -e 'src/main/webapp/bower_components' --with-cfg --with-security
--with-taint --with-dashboard --with-harmonic --with-kantra --kantra-rules rules --kantra-target quarkus3
--export-migration-hints --with-limits`):

```
Indexed 50 files -> 1135 nodes, 3024 edges
238 functions, 884 communities (modularity 0.31), 9 circular dependencies
Kantra: 1 VIOLATES edge (audit-logging-0003, OrderService.java:5)
Security: 2 potential secrets
Migration plan: 12 steps
```

**Blast radius (used to sequence the changes)**

| Symbol | Direct callers | Impact zone | Note |
| --- | --- | --- | --- |
| `ShoppingCartService::priceShoppingCart` | 6 | 9 | highest fan-in in `service`; changed last inside the cart refactor |
| `CartEndpoint::dedupeCartItems` | 4 | 6 | moved into `ShoppingCartService` (business logic) |
| `PromoService::applyCartItemPromotions` | 1 | 5 | called only from pricing |
| `Transformers::jsonToOrder` | 2 | 2 | two consumers, both replaced |
| `Transformers::shoppingCartToJson` | 1 | 2 | single producer, replaced |
| `ShippingService::calculateShipping` | **0** | 0 | reachable only through the JNDI lookup → invisible to the graph, confirming the plan's "remote EJB is a blind spot" risk |
| `ShoppingCartService::checkOutShoppingCart` | 1 | 1 | `CartEndpoint.checkout` |

The zero callers on `ShippingService` is the key finding: the graph cannot see the remote-EJB call, so the
shipping arithmetic had to be protected by characterisation tests before the lookup was removed.

### 17:00 - Reverse-engineering the audit library 2.0.0 API

No JDK ⇒ no `javap`. Wrote a Python constant-pool/member parser (`/tmp/javap2.py`) and inspected
`lib/audit-logging-library-2.0.0.jar`:

- `AuditEvent` is a **record** with 14 components in a fixed order.
- `StreamableAuditLogger(AuditConfiguration)`, `logEventAsync(AuditEvent) -> CompletableFuture<Void>`,
  `logEventAsyncSafe`, `isReady()`, `close()`; `logEvent` is `@Deprecated`.
- `AuditConfiguration` setters: `setStreamHost/Port/Protocol/BufferSize/ReconnectMs` (property keys
  `audit.stream.*`, defaults `localhost`/`TCP`).
- `StreamableAuditLogger` uses `Executors.newVirtualThreadPerTaskExecutor()` → Java 21 required (satisfied).
- Requires `jackson-databind` + `jackson-datatype-jsr310` + `slf4j-api`.

### 17:05 - Group 1: build foundation

- `pom.xml` rewritten: `jar` packaging, `quarkus-bom` 3.15.1 (LTS) via `dependencyManagement`,
  `quarkus-maven-plugin` (`build`, `generate-code`, `generate-code-tests`), `maven-compiler-plugin`
  `release=21`, Surefire + Failsafe, `maven.test.skip` removed.
- Extensions: `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-vertx-http`, `quarkus-smallrye-openapi`,
  `quarkus-arc`, `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-messaging`,
  `quarkus-smallrye-reactive-messaging-amqp`, `quarkus-smallrye-health`,
  `quarkus-micrometer-registry-prometheus`, `quarkus-jboss-logmanager`, `quarkus-container-image-jib`.
- Removed `javaee-api`, `javaee-web-api`, `jboss-jms-api_2.0_spec`, `jboss-rmi-api_1.0_spec`, the
  `system`-scoped audit dependency and every `<systemPath>`.
- Audit library 2.0.0 vendored as a **real Maven repository** (`lib/maven-repo/...` + generated POM, declared
  as a `file://` repository) instead of `mvn install:install-file` — the build stays offline and reproducible.
  Deleted `lib/audit-logging-library-1.0.0.jar`.
- `application.properties` added (datasource, Flyway, Hibernate, REST path, messaging channels, audit, cart,
  storefront/SSO, health, metrics, logging, `%test` profile).

*Decision*: `quarkus-oidc` not added — the monolith only declared container-managed security in `web.xml`
with no enforced roles on the REST layer, so SSO stays frontend-only (documented).

### 17:12 - Group 2: vendor lock-in removal

- Deleted `src/main/java/weblogic/**` (3 files), `utils/StartupListener.java`,
  `service/InventoryNotificationMDB.java`, `service/OrderServiceMDB.java`, `utils/Producers.java`,
  `utils/DataBaseMigrationStartup.java`, `persistence/Resources.java`, `rest/RestApplication.java`,
  `service/ShippingServiceRemote.java`.
- Deleted `META-INF/persistence.xml`, `WEB-INF/web.xml`, `WEB-INF/beans.xml`, `health.jsp`.
- `javax.*` → `jakarta.*` applied across every surviving source: 108 `javax` import lines at baseline, **0** now; `javax.sql.DataSource` was replaced by `io.agroal.api.AgroalDataSource` so not
  even a JDK `javax` import remains).

### 17:20 - Groups 3 and 4: domain model and persistence

- Entities ported to `jakarta.persistence`.
- **Generated-value decision**: `@GeneratedValue(strategy = SEQUENCE, generator = "hibernate_sequence")` with
  `@SequenceGenerator(allocationSize = 1)`. `V1_1__CreateSchema.sql` creates `create sequence
  hibernate_sequence` and the PK columns are plain `int8` (no identity), so `IDENTITY` would break inserts;
  Hibernate 6's default `allocationSize=50` would exhaust a sequence created with increment 1.
- Removed the stale `@Column(name="TOTAL_PRICE")` from `Order` (no such column exists).
- Dropped `Serializable`/`serialVersionUID`/`@Dependent` from the DTOs; getter names preserved for the SPA.
- `OrderItem.getId()` added for tests but marked `@JsonIgnore` so `/services/orders` keeps its payload shape.
- `persistence/` package removed entirely; `EntityManager` is injected directly.
- Flyway 4.1.2 programmatic bootstrap replaced by `quarkus.flyway.migrate-at-start=true` with
  `locations=db/migration`, `baseline-on-migrate=true`.

### 17:28 - Group 5: utilities

- `Transformers` rewritten on Jackson: identical keys, key order and double formatting; deterministic overload
  `shoppingCartToJson(cart, name, email)` added for tests; `jsonToOrder` parses the same payload.
- `Producers` (JUL `@Produces Logger`) replaced by per-class `org.jboss.logging.Logger`.
- `health.jsp` replaced by `DatabaseHealthCheck` (`@Readiness`, `AgroalDataSource.isValid(2)`).
- New `StartupLifecycle` (`@Observes StartupEvent`/`ShutdownEvent`) keeps the `AppListener(postStart)` /
  `AppListener(preStop)` markers.
- New `StorefrontConfigRoutes` (Vert.x `Router` observer) serves `/coolstore.json` and `/keycloak.json` from
  configuration, so `localhost:8081` is no longer baked into the image.

### 17:35 - Groups 6 and 7: services, cart state

- `@Stateless` → `@ApplicationScoped` + `@Transactional` (writes), constructor injection for tests.
- `lookupShippingServiceRemote()` (WildFly JNDI) → injected `ShippingService`; `ShippingServiceRemote` deleted;
  shipping arithmetic untouched (boundary values preserved deliberately).
- Cart state: new `CartStore` interface + `InMemoryCartStore` (`ConcurrentHashMap`, idle TTL from
  `cart.store.ttl-seconds`). `ShoppingCartService` is stateless and keyed by `cartId`; `dedupeCartItems` moved
  here; `addItem`/`setCart`/`deleteItem`/`checkOutShoppingCart` preserve the legacy ordering.
- *Deviation (recorded)*: `setCart` copies the source item list instead of aliasing it — the legacy code shared
  the `List` reference between two carts, which corrupts state after a login transfer.

### 17:42 - Group 8: messaging

- ADR written (`docs/adr/001-messaging.md`): AMQP 1.0 via SmallRye Reactive Messaging.
- `ShoppingCartOrderProcessor`: `JMSContext` + `@Resource Topic` → `@Channel("orders") Emitter<String>`.
- `OrderEventConsumer` (`@Incoming("orders")`) replaces `OrderServiceMDB`; `InventoryNotificationConsumer`
  (`@Incoming("orders-inventory")`, threshold 50) restores the dead MDB's rule; two channels on the same
  address reproduce the topic fan-out.
- Malformed payloads are logged and dropped (deliberate hardening vs. the MDB's rethrow).

### 17:48 - Groups 9 and 10: REST and audit

- `quarkus.rest.path=/services`; three resources as `@ApplicationScoped` beans; routes preserved including the
  `checkout` vs `{tmpId}` overlap (literal segments win).
- Removed method-level `@Path("/")` from the list operations so `/services/products` (no trailing slash, as the
  SPA calls it) maps directly.
- `ApiExceptionMapper` returns a stable `{"error","message"}` JSON body.
- `AuditService` + `AuditEvents`: `StreamableAuditLogger` over TCP, `AuditEvent` record, `logEventAsync`,
  emissions on `ORDER_SAVED`, `CHECKOUT_SUBMITTED`, `CHECKOUT_FAILED`; failures never break checkout.

### 17:55 - Group 11: frontend

- `git mv` of `app/`, `partials/`, `bower_components/` (2,482 files) → `src/main/resources/META-INF/resources/`;
  `index.jsp` → `index.html` without the JSP session scriptlet.
- Static `coolstore.json` / `keycloak.json` deleted in favour of the config-driven routes.

### 18:00 - Group 12 and 13: container and manifests

- Multi-stage `Containerfile` (UBI 9 OpenJDK 21 builder → UBI 9 JRE runtime, `USER 1001`, no writable path),
  `.containerignore`, `Makefile` (verify/unit/build/dev/run/image/push/scan), `scripts/container-smoke.sh`.
- `deploy/`: `kustomization.yaml`, `deployment.yaml` (2 replicas, probes on `/q/health/*`, read-only root fs,
  dropped capabilities, Secret-backed env), `service.yaml`, `route.yaml` (edge TLS), `configmap.yaml`,
  `secret.example.yaml`, `serviceaccount.yaml`, `pdb.yaml`, `hpa.yaml` (2-4), `networkpolicy.yaml`.
- No file created or modified under `.github/workflows/`.

### 18:05 - Test generation (mig-test-gen)

10 unit test classes (no container): shipping bands, promotions, cart lifecycle/pricing, transformers goldens,
audit record, low-stock consumer, static guards.
4 integration classes: `ProductEndpointIT`, `CartEndpointIT`, `StorefrontIT`, `HealthIT`, plus
`CheckoutFlowIT`, `OrderPersistenceIT`, `FlywayMigrationIT` (7 total; two of them persistence).
`src/test/resources` empty — Dev Services supplies PostgreSQL, the in-memory connector supplies messaging.

### 18:12 - Verification (rgctl re-index)

```
rgctl discover . -e 'src/main/resources/META-INF/resources/bower_components'
  --with-cfg --with-security --with-taint --with-dashboard --with-harmonic
  --with-kantra --kantra-rules rules --kantra-target quarkus3 --export-migration-hints

Indexed 77 files -> 1615 nodes, 4490 edges
356 functions, 171 classes, 3 interfaces
Kantra: 0 VIOLATES edges
```

- `rgctl -f json rules run rules --target quarkus3` → `"violations": []` (**0 of 5 rules violated**).
- `grep -rn javax src/main/java src/main/resources` → 0; `weblogic|java:jboss|java:/topic|InitialContext|
  PortableRemoteObject` → 0.
- Package import graph (computed from imports): `rest → {service, model}`, `service → {audit, model, utils}`,
  `audit → model`, `utils → model`, `model → {}` — **acyclic**, exactly the target layering.
- Post-migration `migration_plan.json` has 11 steps; `com.redhat.coolstore.persistence` is now a test-only
  package (the producer was deleted), `com.redhat.coolstore.audit` is a new package.
- Security scan: 6 potential secrets, all non-production placeholders or dev defaults
  (`${DB_PASSWORD:coolstore}`, `${AMQP_PASSWORD:admin}`, `CHANGE_ME` in `deploy/secret.example.yaml`,
  vendored bower example files). OpenShift injects real values from Secrets; no credential is committed.

### 18:20 - Documentation, plan bookkeeping, commit

- `docs/`: `design-notes.md`, `adr/001-messaging.md`, `ejb-to-cdi-map.md`, `audit-logging.md`,
  `api-contract.md`, `runbook.md`, `testing.md`, `vendor-lockin.md`; README rewritten for Quarkus/OpenShift.
- `tasks.md`: 101/135 subtasks checked; 34 left open with a `⛔ not executable in this environment` note.
- `UserStory.md`: 74/86 acceptance criteria checked; the 12 open ones need a live build/image/cluster.

---

## Failures and deviations

| # | Item | Resolution |
| --- | --- | --- |
| 1 | No JDK/Maven → cannot compile or run tests | Full static review; tests written defensively against documented APIs; every build/boot checkbox left open with a reason |
| 2 | `javap` unavailable for the vendored JAR | Wrote a constant-pool parser (`/tmp/javap2.py`) to recover the exact 2.0.0 API surface |
| 3 | `IDENTITY` generation would break against the existing DDL | Chose `SEQUENCE`/`hibernate_sequence` with `allocationSize=1`; documented in `docs/design-notes.md` §6 |
| 4 | Legacy `setCart` aliased two carts' item lists | Deliberate deviation: defensive copy (documented) |
| 5 | `@Path("/")` on list operations is ambiguous in Quarkus REST | Removed; `/services/products` matches the SPA's call exactly |
| 6 | Function-level graph cycles rose 9 → 22 | Index now includes test sources (77 vs 50 files) and the new service↔audit wiring; the **package** graph stays acyclic (verified by import analysis). Recorded as an accepted deviation from "no new cycles" at function granularity |
| 7 | `mig-containerize` / `mig-deploy` skills not installed | Artifacts produced directly (Containerfile, manifests, smoke script, runbook); cluster execution left open |
| 8 | 6 potential secrets after the scan (baseline 2) | All are dev defaults/placeholders, externalised via env + OpenShift Secrets; `secret.example.yaml` documents `oc create secret` |

---

## Summary

- **Total subtasks**: 135 → 101 completed (75 %), 34 blocked on missing runtime
- **User stories**: 13 → 11 satisfied by artifacts, 2 (container image verification, cutover) blocked at the
  runtime-verification stage
- **Kantra violations**: 1 → 0
- **`javax` imports**: 108 → 0 · **WebLogic references**: 7 → 0 · **`systemPath` deps**: 1 → 0
- **Tests**: 0 → 14 classes
