# Migration Execution Log

**Started**: 2026-11-06T04:26Z · **Migration**: JBoss EAP 7.4 / Java EE 7 (javax) → Quarkus 3.27.6 LTS / Java 21 / Jakarta EE 10 / OpenShift
**Plan**: `mig-plan-workspace/tasks.md` (9 groups) · **Spec**: `mig-prompt-workspace/migration-prompt.md`

## Environment preparation
- No JDK/Maven in the harness. Installed Temurin JDK 21.0.13+9 and Maven 3.9.9 under `/tmp/tools`
  (not committed). No PostgreSQL, Kafka, Keycloak, Podman or OpenShift cluster is reachable.
- rgctl v0.4.20: re-indexed the migrated tree (`rgctl discover . -l java --with-cfg`) → 0 rule violations.

## Timeline

| Time | Task | Status | Notes |
|---|---|---|---|
| 04:28 | 1.2 test harness | ✅ | `src/test/java`, `maven.test.skip` removed, JUnit 5 + REST Assured + quarkus-junit5; H2 (PostgreSQL mode) + in-memory messaging so the suite runs with no external service |
| 04:29 | 1.3–1.5 characterization | ✅ | 84 tests: REST contracts (9 methods), pricing/shipping tiers, Transformers golden documents, Flyway from-empty/from-migrated |
| 04:28 | 2.1 pom → Quarkus 3.27.6 | ✅ | BOM + plugin, `jar` fast-jar, `maven.compiler.release=21`, Java EE 7 specs and war-plugin removed |
| 04:29 | 2.2 jakarta sweep | ✅ | zero `javax.*` EE imports (guardrail test); remaining `javax.` occurrence is a javadoc word, reworded |
| 04:29 | 2.3 configuration | ✅ | `application.properties` with dev/test/prod profiles; `web.xml`, `beans.xml` deleted |
| 04:29 | 2.4 JAXB → Jackson | ✅ | `@XmlRootElement` removed from `InventoryEntity`; Jackson is the only JSON stack |
| 04:29 | 2.5 vendor dead code | ✅ | `weblogic/**`, `StartupListener`, `NonCatalogLogger`, `InventoryNotificationMDB` deleted + decision 0001 |
| 04:30 | 3.1–3.4 persistence | ✅ | explicit `@Column` names (Hibernate 6 naming would drift), `Resources` deleted, datasource from env, `quarkus-flyway` replaces `DataBaseMigrationStartup`, migrations byte-identical |
| 04:31 | 3.5 Flyway/H2 validation | ✅ | first green `@QuarkusTest`; V1_1 + V1_2 apply on an empty DB, re-`migrate()` executes 0 |
| 04:31 | 4.1 Transformers → Jackson | ✅ | golden documents (empty/single/multi) byte-identical incl. key order; malformed input still unchecked |
| 04:32 | 4.2 Logger producer | ✅ | `org.jboss.logging.Logger` injection; `Producers` deleted |
| 04:33 | 4.3 audit 2.0.0 dependency | ✅ | normal `compile` scope via project-local repo `lib/repo`; no system scope/systemPath |
| 04:34 | 4.4 OrderService audit | ✅ | `StreamableAuditLogger` + 14-component `AuditEvent` record + `logEventAsync(...).exceptionally(...)`; decision 0002 |
| 04:34 | 4.5 dev/test transport | ✅ | `AuditLoggerProducer` selects `NoOpAuditLogger` when `audit.enabled=false` |
| 04:35 | 5.1–5.2 CDI + JNDI removal | ✅ | EJBs → `@ApplicationScoped` + `@Transactional`; `ShippingServiceRemote` + `ejb:/ROOT/…` lookup deleted |
| 04:36 | 5.3 cart state | ✅ | `CartStore` keyed by `cartId`, bounded + idle eviction; `docs/cart-state.md` |
| 04:37 | 5.4–5.5 messaging | ✅ | `Emitter<String>` on `orders-out` → topic `orders`; `@Incoming("orders")` `OrderConsumer` |
| 04:37 | 5.6 InventoryNotificationMDB | ✅ | deleted (never activated, WebLogic-only JNDI) |
| 04:38 | 5.6/5.8 idempotency | ✅ | `OrderMessageDeduplicator` + duplicate-delivery test |
| 04:38 | 6.1–6.2 REST scopes | ✅ | `CartEndpoint`/`ProductEndpoint`/`OrderEndpoint` `@ApplicationScoped @Blocking`; 9 methods unchanged |
| 04:38 | 6.3 SPA hosting | ✅ | `META-INF/resources` + static `index.html`; `health.jsp` → `/q/health` |
| 04:39 | 6.4/6.5 runtime config | ✅ | `/coolstore.json` + `/keycloak.json` generated from config (`ConfigEndpoint`, `SpaConfigRoutes`); no `localhost` literal |
| 04:39 | 7.1–7.3 image | ✅ | multi-stage `Containerfile`, UBI 9 / OpenJDK 21, UID 185, full env contract in `docs/container.md` |
| 04:40 | 8.1–8.5 manifests | ✅ | Kustomize base + dev/prod overlays, probes, `maxUnavailable: 0`, NetworkPolicy, HPA, cutover/rollback docs |
| 04:40 | 9.4–9.5 docs | ✅ | README, MIGRATION.md, `docs/{baseline,data,audit,services,cart-state,web,container,openshift}.md`, 2 ADRs |
| 04:41 | Verification | ✅ | `mvn -B verify`: **84 tests, 0 failures**; `rgctl rules run rules`: **0 violations** |

## Failures encountered and resolved

1. `smallrye-in-memory` unknown connector → added `smallrye-reactive-messaging-in-memory` (test scope).
2. Empty-string config defaults rejected by SmallRye Config (`SRCFG00040`) → `Optional<String>` in `ConfigEndpoint`.
3. `SRMSG00073`: a channel cannot be both incoming and outgoing → split into `orders-out` (producer) /
   `orders` (consumer) mapping to one Kafka topic.
4. `InMemoryConnector` has no CDI bean in this Quarkus version → replaced with an `@Alternative`
   `OrderPublisher` recorder plus a direct `OrderConsumer.consume` test (no mocking library needed).
5. `cartJourney` expected the promo price on the item: `dedupeCartItems` re-reads the catalogue price
   (pre-existing EAP behaviour) — the characterization assertion was corrected to pin the real behaviour.
6. Guardrail tests tripped on their own comments, on a binary image asset and on a `systemPath` word in a
   POM comment → comment-stripping + text-only resource scanning.
7. Audit NDJSON serialises `Instant` as epoch seconds and the enum as `"success"` → assertions pin the
   real wire format.

## Not executed (require infrastructure outside this environment)

Story 1.6/1.9/1.10 (baseline load test, reference image, baseline namespace), 2.9/2.10, 3.7/3.8, 4.7/4.8,
5.9/5.10, 6.7/6.8, 7.5/7.6 (registry push), 8.7/8.8 (cluster deploy), 9.1–9.3, 9.6–9.8 (cluster E2E,
performance comparison, cutover, release freeze). Artifacts for them (Containerfile, manifests, env
contract, gate thresholds) are in place and documented as the deployment-stage acceptance gate.
