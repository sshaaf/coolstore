# Migration Execution Log

**Started**: 2026-11-06 15:36 UTC
**Completed**: 2026-11-06 16:05 UTC
**Migration**: CoolStore Java EE 7 (JBoss EAP 7.4, `ROOT.war`) → Quarkus 3.40.1 (Jakarta EE 10) / Java 21 on OpenShift
**Plan**: `mig-plan-workspace/tasks.md` (12 task groups / 12 user stories / 124 subtasks)
**Prompt**: `mig-prompt-workspace/migration-prompt.md`

---

## Execution environment

| Item | Value |
|---|---|
| Sandbox | no JDK, no Maven, no container runtime, no PostgreSQL/Redis/Artemis/Keycloak |
| Tooling installed to `/tmp` | Temurin JDK 21.0.13, Apache Maven 3.9.9 |
| rgctl | v0.4.20; index rebuilt with `rgctl discover . -l java --with-harmonic` (daemon cache, `.rgctl/` gitignored) |
| Network | Maven Central reachable → full dependency resolution and real compilation/test execution |

---

## Timeline

### 15:36 – Preparation and impact analysis
* Ran `rgctl discover . -l java --with-harmonic`; verified with `metrics --pagerank` and
  `communities list` (index was missing at session start; re-index required).
* Blast-radius checks before restructuring:
  * `priceShoppingCart` – 6 direct callers, impact zone 9 (score 40.45)
  * `shoppingCartToJson` – 1 caller (`process`), impact zone 2
  * `dedupeCartItems` – 4 callers, impact zone 6
  * `Promotion`/`CatalogItemEntity` top pagerank nodes
  * Conclusion: no high blast-radius Java symbol → mechanical rewrite is safe; cart/JSON
    rewrites done last, after their callers were already stable.
* **Status**: ✅

### 15:38 – Task group 1: baseline harness
* Golden fixtures authored under `src/test/resources/golden/` (products, cart, JMS body)
  with provenance notes (`golden/README.md`).
* Characterization suite written framework-free (JUnit 5 + AssertJ + Mockito): shipping
  brackets, insurance tiers, promotions, cart pricing, dedupe, JSON round-trip.
* `docs/baseline.md` (environment matrix + re-recording procedure) and
  `docs/perf-baseline.md` (fixed load profile, +10% gate, methodology).
* **Deviation**: legacy WAR could not be *executed* (no JBoss EAP, no podman) → fixtures
  were derived from the SQL seed data and the legacy code paths, and the re-recording
  procedure is documented. Logged as a known limitation.
* **Status**: ✅ (1.1/1.5/1.7 partially blocked, see report)

### 15:40 – Task group 2: Quarkus foundation
* `pom.xml` rewritten: Quarkus BOM 3.40.1, `jar` packaging, `finalName=coolstore`,
  `maven.compiler.release=21`, extension set (rest, rest-jackson, jackson, hibernate-orm,
  jdbc-postgresql, agroal, flyway + flyway-database-postgresql, smallrye-health, oidc,
  redis-client, micrometer-prometheus, artemis-jms), test stack, JaCoCo.
* Mechanical `javax.*` → `jakarta.*` flip across all remaining classes (0 hits left).
* Deleted `weblogic/**` (3 stubs), `StartupListener`, `InventoryNotificationMDB`,
  `ShippingServiceRemote`, `Resources`, `DataBaseMigrationStartup`, `RestApplication`,
  `OrderServiceMDB`, `persistence.xml`, `index.jsp`, `health.jsp`, `WEB-INF/{web.xml,beans.xml}`.
* `application.properties`: env-indirected only (`${ENV:default}`), REST base path
  `/services`, datasource, Flyway baseline 1.1, Redis, Artemis, audit stream, OIDC, health,
  metrics, shutdown, JSON logging.
* **Issue found and fixed**: `quarkus.artemis.health.enabled` was parsed as a *named*
  Artemis configuration called "health" → app failed to start ("url is not set and
  devservices is activated"). Replaced with `quarkus.artemis.health-exclude`.
* **Issue found and fixed**: Quarkus platform version had to match the Artemis extension's
  build (Quarkus 3.40.0) — 3.15.7/3.20.6 produced plugin `NoSuchMethodError` /
  `FooterLogBuildItem` failures. Pinned platform 3.40.1.
* **Verified**: `mvn -B package` → `target/quarkus-app`; app boots; `/q/health/live` 200,
  `/` 200 (SPA), `/q/metrics` 200, `/services/orders` 500 (no DB, expected).
* **Status**: ✅

### 15:45 – Task group 3: persistence
* 4 entities on Jakarta Persistence, table/column names unchanged; `@XmlRootElement`
  removed from `InventoryEntity`.
* Sequence generators pinned to the existing `hibernate_sequence` with `allocationSize=1`
  (Hibernate 6 would otherwise expect per-entity sequences absent from the Flyway schema).
* Datasource/persistence.xml replaced by Quarkus config; `EntityManager` injected
  constructor-side (no producer).
* Flyway owns the schema (`migrate-at-start`, `baseline-on-migrate`, `baseline-version=1.1`).
* **Status**: ✅ (3.9 cluster deploy blocked)

### 15:48 – Task group 4: services EJB → CDI
* `CatalogService`, `ProductService`, `OrderService`, `ShippingService` → `@ApplicationScoped`
  with constructor injection; `PromoService` unchanged (already CDI).
* `@Transactional` on `CatalogService.updateInventoryItems`, `OrderService.save`,
  `OrderService.processOrder` (order + inventory atomically).
* `ShoppingCartService` no longer performs a JNDI lookup; `ShippingService` injected.
* `InventoryNotificationMDB` deleted (decision D4).
* **Status**: ✅

### 15:50 – Task group 5: cart state externalization
* `CartStore` interface + `RedisCartStore` (key `cart:{cartId}`, TTL, JSON via Jackson,
  `CartStoreException` on outage).
* `ShoppingCartService` stateless: all five use cases (get, add, set/copy, delete, checkout)
  moved from the endpoint into the service, dedupe rewritten with `LinkedHashMap` (stable
  order, same result).
* `CartEndpoint` no longer `@SessionScoped`; identity comes from `{cartId}` only.
* **Status**: ✅ (5.9 cluster deploy blocked)

### 15:52 – Task group 6: REST parity
* `quarkus.rest.path=/services` replaces `@ApplicationPath`; `RestApplication` deleted.
* Endpoints ported 1:1 (paths, verbs, status codes, 2-segment vs 3-segment disambiguation).
* `Transformers` rewritten with Jackson (`ObjectNode` to preserve field **order**).
* `ErrorMappers` adds structured JSON error bodies and correlation ids; status codes
  unchanged (500 on failure, 204 on null entity).
* `docs/api-parity.md` published.
* **Status**: ✅ (6.9 cluster diff blocked)

### 15:55 – Task group 7: messaging
* Vendored audit jar published as a real Maven repository layout (`lib/maven-repo`,
  groupId/artifactId/version + POM); POM uses a normal compile dependency and a file-based
  `<repository>`; no `system` scope.
* `ShoppingCartOrderProcessor` → `ConnectionFactory`/`JMSContext` producer, same destination
  and body, same log line.
* `OrderMessageConsumer` replaces the MDB: `CLIENT_ACKNOWLEDGE`, ack only after persist +
  inventory deduction; failures left unacknowledged for redelivery → `orders.dlq` after
  bounded attempts; startup is tolerant of an unavailable broker.
* **Status**: ✅ (7.10 broker deploy blocked)

### 15:57 – Task group 8: audit v2
* Inspected the jar (`javap`) to derive the real v2 API: 14-component `AuditEvent` record,
  `StreamableAuditLogger`, `logEventAsync`, `logEventAsyncSafe`, `isReady`, `close`.
* `AuditEventFactory` maps order events; `AuditPublisher` streams NDJSON over TCP,
  non-blocking, readiness-gated (probe cached for `reconnect-ms`), no-op when disabled,
  closed on `@PreDestroy`.
* **Status**: ✅ (8.9 collector deploy blocked)

### 15:59 – Task group 9: web/OIDC/health
* `webapp/` → `META-INF/resources/` (SPA, bower_components, partials, `coolstore.json`,
  `keycloak.json`); `index.jsp` → `index.html` without the servlet-session scriptlet.
* `keycloak.json` no longer hardcodes `localhost:8081` (now `/auth/`, ConfigMap-overridable
  via `QUARKUS_HTTP_STATIC_LOCATIONS`).
* SmallRye Health replaces `health.jsp`; Micrometer/Prometheus added; OIDC wired but
  disabled by default (decision D6).
* **Status**: ✅ (9.9 Keycloak deploy blocked)

### 16:01 – Task group 10: quality gates
* surefire (unit) + failsafe (`*IT`, opt-in via `-Pintegration`) + JaCoCo report/check.
* 67 unit tests green; **85.3% line coverage** (526/617): service 82.6%, utils 91.8%,
  model 89.2%, rest 95.3%, audit 75.7%. `mvn -B verify` enforces the 80% bundle limit.
* `docs/testing.md` documents layers, results, and known gaps.
* **Status**: ✅ (10.6/10.7/10.9/10.10 environment-blocked)

### 16:03 – Task groups 11–12: container and deployment
* Multi-stage `Containerfile` (ubi9/openjdk-21 build → openjdk-21-runtime, UID 185,
  read-only rootfs, `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, dependency-cached build).
* `.containerignore`; SBOM/scan commands documented in README.
* OpenShift kustomize base: Deployment (2 replicas, 3 probes, resources, securityContext),
  Service, Route (TLS edge), ConfigMap, Secret template, ServiceAccount, HPA 2→6, PDB,
  NetworkPolicy (router ingress; DB/Redis/Artemis/Keycloak/collector egress).
* `docs/runbook.md`: health, degradation matrix, DLQ handling, ≤15 min rollback.
* **Status**: ✅ (11.4/11.5 image publish+scan, 12.x cluster cutover blocked)

### 16:05 – Final verification
* `mvn -B verify` → **BUILD SUCCESS**, 67 tests, coverage gate met.
* `grep -r '^import javax\.'` → 0; no `weblogic` files; no `jakarta.ejb` imports.
* Packaged app boot check: `/q/health/live` 200, `/` 200, `/q/metrics` 200.
* `.rgctl/` removed and gitignored; no `.github/workflows/` changes.

---

## Summary

| Metric | Value |
|---|---|
| Task groups delivered | 12 of 12 (code/artifacts) |
| Subtasks completed | 96 |
| Subtasks blocked by sandbox (cluster/container runtime) | 28 |
| Unit tests | 67 passing, 0 failures |
| Line coverage (unit) | 85.3% (gate 80%) |
| Files deleted | 12 (vendor shims, EJB/legacy glue, JSP/WEB-INF) |
| Files added | 30+ (audit, cart store, consumer, tests, manifests, docs) |
