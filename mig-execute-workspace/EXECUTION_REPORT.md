# Migration Execution Report

**Migration**: CoolStore monolith — JBoss EAP 7.4 / Java EE 7 (`javax.*`) → **Quarkus 3.27.6 (LTS) / Java 21 / Jakarta EE 10 / OpenShift**
**Started**: 2026-11-06T04:26Z · **Completed**: 2026-11-06T04:41Z
**Status**: ✅ Completed — all code, test, packaging and manifest work done and verified locally;
deployment-stage activities (cluster E2E, performance comparison, cutover) remain as documented gates.

---

## Executive summary

The application was ported in place to Quarkus 3 on Java 21 with Jakarta namespaces, a CDI service
model, SmallRye Reactive Messaging instead of JMS, `quarkus-flyway` on the unchanged PostgreSQL
schema, `audit-logging-library` 2.0.0 with NDJSON-over-TCP audit events, and a static SPA served from
`META-INF/resources`. The nine REST endpoints, the pricing rules, the checkout wire document and the
Flyway checksums are pinned by a **new 84-test suite that runs on plain `mvn verify`** (H2 in
PostgreSQL mode, in-memory messaging, no-op audit — no Docker, broker or Keycloak needed).
Container and OpenShift artifacts are authored and documented.

## Statistics

| | Count |
|---|---|
| User stories addressed | 9 of 9 (7, 8 and 9 partially: artifacts + docs done, cluster execution pending) |
| Task groups addressed | 9 of 9 |
| Subtasks completed | 47 |
| Subtasks deferred (infrastructure-dependent) | 15 |
| Tests written / passing | **84 / 84** |
| `rgctl` rule violations after migration | **0** (was 1: `audit-logging-0003`) |
| Java files in `src/main/java` | 24 (was 30; 6 legacy/vendor files deleted) |

## Verification performed

```
mvn -B verify            → Tests run: 84, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
mvn -B package           → target/quarkus-app/ (fast-jar, quarkus-run.jar)
rgctl discover . -l java --with-cfg   → index rebuilt on the migrated tree
rgctl -f json rules run rules         → {"violations": []}
```

| Suite | Tests | Covers |
|---|---|---|
| `ShippingServiceTest` | 28 | shipping tiers 2.99/4.99/6.99/8.99/10.99, insurance 2 %/1.5 %/1 %, HALF_UP scale 2, nulls |
| `PromoServiceTest` | 5 | 25 % item promo, free shipping ≥ 75, defensive copies |
| `PricingRulesTest` | 6 | full pricing pipeline against the seeded catalogue, idempotency, per-cart isolation |
| `TransformersTest` | 10 | golden checkout documents (empty/single/multi), round trip, malformed/missing input, `toProduct` |
| `RestContractTest` | 11 | all 9 endpoint methods, SPA shell, static assets, `/coolstore.json`, `/keycloak.json`, `/q/health/*` |
| `PersistenceContractTest` | 5 | Flyway from-empty + already-migrated, entity mapping, order + items + FK, inventory decrement |
| `PersistenceSmokeTest` | 1 | schema/seed data through `CatalogService` |
| `OrderMessagingTest` | 5 | `@Incoming("orders")` wiring, checkout payload, cart reset, consumer persistence + decrement, duplicate idempotency |
| `AuditLoggingTest` | 6 | 14-component record, NDJSON field set, closed logger, unreachable collector, no-op logger |
| `MigrationGuardrailsTest` | 7 | no `javax.*` imports, no Java EE/JBoss artifacts, no system scope, legacy files gone, migrations unchanged, no localhost Keycloak URL |

## Acceptance criteria status

* **Story 1** — endpoint inventory, pricing tables, checkout wire contract and Flyway behaviour are
  pinned executably (`docs/baseline.md`). ⚠️ WAR checksum and p95/throughput numbers were not
  re-measured: no JBoss EAP instance, PostgreSQL or Keycloak exists in this environment.
* **Story 2** — Quarkus BOM/plugin, `jar`, Java 21, zero `javax.*` imports, no EE specs, `web.xml`/
  `beans.xml` gone, dev/prod profiles, `/q/health/live` green in `@QuarkusTest`. ✅
* **Story 3** — entities map to `PRODUCT_CATALOG`/`INVENTORY`/`ORDERS`/`ORDER_ITEMS` with explicit
  column names, `generation=none`, env-only datasource, Flyway replaces the startup bean with
  byte-identical migrations, `Resources`/`persistence.xml` removed without breaking injection. ✅
* **Story 4** — Jackson `Transformers` (byte-identical output), JBoss Logging, 2.0.0 normal dependency,
  `StreamableAuditLogger`, 14-component record, `logEventAsync`, no TCP in tests, 0 rule violations. ✅
* **Story 5** — EJBs are CDI beans with explicit transactions, remote lookup gone, cart state is a
  documented `cartId`-keyed store, `Emitter` on `orders-out`, `@Incoming("orders")` consumer identical
  logic, duplicates guarded, competing-consumer config. ✅ (multi-replica behaviour needs a cluster.)
* **Story 6** — 9 endpoints unchanged, scopes fixed, SPA in `META-INF/resources`, `index.html`,
  `/q/health`, no `localhost` Keycloak URL, runtime-configurable `coolstore.json`/`keycloak.json`. ✅
  (Browser SSO journey needs a Keycloak instance.)
* **Story 7** — multi-stage `Containerfile`, UID 185, read-only friendly, complete env contract, no
  `systemPath`. ✅ authored; ⚠️ image build/scan needs Podman + registry.
* **Story 8** — Kustomize base + dev/prod overlays, probes, `maxUnavailable: 0`, grace period,
  NetworkPolicy/HPA, dependency matrix, cutover/rollback runbook, `.github/workflows/` untouched. ✅
  authored; ⚠️ apply/verify needs a cluster.
* **Story 9** — legacy artifacts removed, README/MIGRATION/docs/ADRs complete. ⚠️ Cluster regression
  run, performance comparison, cutover and enablement are deployment-stage activities.

## Deferred items (need infrastructure)

Baseline load test + reference image/namespace (1.6, 1.9, 1.10); intermediate containerize/deploy
hooks (2.9, 2.10, 3.7, 3.8, 4.7, 4.8, 5.9, 5.10, 6.7, 6.8); registry push (7.5, 7.6); cluster deploy
and digest verification (8.7, 8.8); OpenShift regression/E2E, performance comparison, cutover and
release freeze (9.1–9.3, 9.6–9.8). Each has its artifacts, configuration and acceptance gate written down.

## Recommendations before production

1. Run `docs/openshift.md` §Verification on a stage cluster (probes, 2-replica consumption, rolling
   update, rollback drill) and record the p95 comparison against the EAP baseline.
2. Get sign-off (or keep `OIDC_ENABLED=false`) on backend bearer protection for `/services/**`.
3. Decide the cart-store follow-up (sticky routing now, Infinispan/Redis later) and the durable
   duplicate-delivery guard.
4. Provide the audit collector or set `AUDIT_ENABLED=false` per environment.
5. Build and scan the image; record SBOM and accepted AngularJS 1.x findings.
