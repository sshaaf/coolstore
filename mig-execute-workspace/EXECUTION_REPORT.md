# Migration Execution Report

**Migration**: CoolStore - Java EE 7 monolith (JBoss EAP 7.4, WAR) → **Quarkus 3.15 / Java 21 / Jakarta EE 10**,
single deployable on **Red Hat OpenShift**
**Started / completed**: 2026-11-06 (single execution session)
**Plan**: `mig-plan-workspace/tasks.md` (14 task groups, 135 subtasks) · `mig-plan-workspace/UserStory.md`
(13 stories, 86 acceptance criteria) · `mig-prompt-workspace/migration-prompt.md`

---

## Executive summary

The application has been migrated in place: every Java EE 7 API, EJB, JMS topic, JNDI lookup, WebLogic shim and
the WAR packaging are gone, replaced by Quarkus 3 / Jakarta EE 10 equivalents. The storefront contract
(`/services/*`, JSON payload shapes, pricing arithmetic) is preserved, the audit library is upgraded to the
record-based 2.0.0 API with real async TCP emission, and the project now ships a multi-stage container image
definition, OpenShift manifests, an operations runbook and 14 test classes.

**Status**: ⚠️ **Completed with environment-blocked verification.** All source, configuration, test, container
and deployment artifacts are delivered and statically verified; the environment has **no JDK, Maven, Podman or
`oc` binary**, so compile/test/image/cluster steps could not be executed and are reported as open.

| Signal | Baseline | Result |
| --- | --- | --- |
| Konveyor (Kantra) violations, target `quarkus3` | 1 (`audit-logging-0003`) | **0** |
| `javax.*` import lines in `src/main/java` | 108 | **0** |
| WebLogic references | 7 (2 packages, listener, MDB, JNDI factory) | **0** |
| `java:jboss/…`, `java:/topic/…` JNDI names | 3 | **0** |
| Remote-EJB / JNDI lookups | 2 | **0** |
| `<systemPath>` dependencies | 1 | **0** |
| Package-level dependency cycles | 0 (layered) | **0** (verified by import analysis) |
| Test classes | 0 (`maven.test.skip=true`) | **14** |

---

## Statistics

| Metric | Count |
| --- | --- |
| User stories | 13 → 11 satisfied by artifacts, 2 blocked at runtime verification (story 11 image, story 12/13 cluster) |
| Task groups | 14 → all delivered |
| Subtasks | 135 → **101 completed**, 34 blocked on missing toolchain |
| Acceptance criteria | 86 → **74 met**, 12 unverifiable here |
| Java files in `src/main/java` | 29 (from 30, net of 8 deletions and 7 additions) |
| Test classes | 14 (7 unit, 7 integration) |
| Documentation | README + 8 documents (`docs/`) |

---

## User story results

| # | Story | Status | Evidence |
| - | ----- | ------ | -------- |
| 1 | Build on Java 21 with Quarkus | ✅ artifacts (build unverified) | `pom.xml` (jar, BOM 3.15.1, release 21, no `systemPath`), vendored Maven repo, `application.properties`; `mvn verify` not runnable here |
| 2 | No WebLogic/JBoss/`javax` | ✅ verified statically | `weblogic/**` deleted, `StartupLifecycle` CDI observer, 0 `javax` imports, `InventoryNotificationMDB` deleted, descriptors removed, rgctl shows no weblogic nodes |
| 3 | Persist with Hibernate ORM 6 | ✅ verified statically | jakarta.persistence entities, explicit `SEQUENCE` strategy, stale `TOTAL_PRICE` removed, DTO field names unchanged, `model` has no vendor edges |
| 4 | Flyway 10 migrations | ✅ artifacts (DB run unverified) | `quarkus-flyway` config, `DataBaseMigrationStartup` deleted, `FlywayMigrationIT` (Testcontainers `postgres:15`, seed + idempotency assertions) |
| 5 | Pricing/promotion/shipping parity | ✅ unit-tested (not executed) | `ShippingServiceTest` (all bands), `PromoServiceTest`, `ShoppingCartServiceTest`; direct `ShippingService` injection |
| 6 | Cart isolation | ✅ unit-tested (not executed) | `CartStore` + `InMemoryCartStore` (TTL), `dedupeCartItems` moved to the service, isolation/transfer/delete tests |
| 7 | Async order processing | ✅ artifacts (broker run unverified) | `Emitter<String>` + two `@Incoming` channels, `CheckoutFlowIT`, ADR 001 |
| 8 | Unchanged REST contract | ✅ artifacts (run unverified) | `quarkus.rest.path=/services`, 9 route methods, golden JSON tests, routing test for the checkout/transfer overlap |
| 9 | Audit library 2.0.0 | ✅ verified statically | `StreamableAuditLogger`, 14-component `AuditEvent` record, `logEventAsync`, 0 Kantra violations |
| 10 | Storefront unchanged | ✅ artifacts (run unverified) | assets moved to `META-INF/resources`, `index.html`, config-driven `coolstore.json`/`keycloak.json`, `StorefrontIT` |
| 11 | Non-root reproducible image | ⚠️ artifact only | multi-stage `Containerfile` (UBI 9 + JDK 21, `USER 1001`), `.containerignore`, `scripts/container-smoke.sh`; `podman` unavailable |
| 12 | OpenShift deployment | ⚠️ artifact only | `deploy/` (Deployment, Service, Route, ConfigMap, Secret template, SA, PDB, HPA, NetworkPolicy), runbook; `oc` unavailable |
| 13 | Cutover with rollback | ⛔ blocked | procedure documented in `docs/runbook.md` §7; requires a cluster |

---

## What changed (by package)

### `model`
- `CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem` → `jakarta.persistence`; `Serializable` and
  `serialVersionUID` dropped.
- `Order`/`OrderItem` PK generation: `SEQUENCE` + `hibernate_sequence`, `allocationSize = 1` (the DDL creates the
  sequence and plain `int8` columns).
- `Order`: removed the stale `@Column(name="TOTAL_PRICE")`; EAGER `@OneToMany` + `@JoinColumn(ORDER_ID)` +
  `orphanRemoval` kept.
- `OrderItem.getId()` added with `@JsonIgnore` (payload parity).
- `Product`, `Promotion`, `ShoppingCart`, `ShoppingCartItem`: plain POJOs, same getters (SPA contract).

### `service`
- `CatalogService`, `ProductService`, `OrderService`, `PromoService`, `ShippingService`, `ShoppingCartService`
  → `@ApplicationScoped` (+ `@Transactional` on persistence paths), constructor injectable.
- `ShoppingCartService`: stateless, keyed by `cartId` via `CartStore`; remote-EJB lookup replaced by injection;
  `dedupeCartItems` absorbed from the resource.
- New: `CartStore`, `InMemoryCartStore`, `OrderEventConsumer`, `InventoryNotificationConsumer`.
- `ShoppingCartOrderProcessor`: `Emitter<String>` over AMQP.
- Deleted: `OrderServiceMDB`, `InventoryNotificationMDB`, `ShippingServiceRemote`.

### `rest`
- `ProductEndpoint`, `OrderEndpoint`, `CartEndpoint` ported to `jakarta.ws.rs` (stateless).
- New `ApiExceptionMapper` (stable `{"error","message"}` body, no stack traces).
- Deleted `RestApplication` (`quarkus.rest.path=/services`).

### `utils`
- `Transformers` → Jackson, byte-compatible wire format, deterministic test overload.
- New: `StartupLifecycle`, `DatabaseHealthCheck`, `StorefrontConfigRoutes`.
- Deleted: `Producers`, `DataBaseMigrationStartup`, `StartupListener`.

### `audit` (new)
- `AuditService` (`StreamableAuditLogger`, MicroProfile Config, async, close on shutdown).
- `AuditEvents` (record factory: `ORDER_SAVED`, `CHECKOUT_SUBMITTED`, `CHECKOUT_FAILED`).

### Resources and static content
- `application.properties` (datasource, Flyway, Hibernate, REST, messaging, audit, cart, SSO, health, metrics,
  logging, `%test`/`%prod` profiles).
- `db/migration/V1_1`, `V1_2` unchanged (schema is the source of truth).
- SPA moved to `src/main/resources/META-INF/resources` (`index.html`, `app/`, `partials/`,
  `bower_components/` 2,482 files), JSP removed.

### Build, container, deployment, docs
- `pom.xml`, `lib/maven-repo` (vendored audit 2.0.0), `Containerfile`, `.containerignore`, `Makefile`,
  `scripts/container-smoke.sh`, `deploy/*` (9 manifests), `docs/*` (8 documents), rewritten `README.md`.

---

## Verification performed (and what could not be)

### Verified in this environment

| Check | Command / method | Result |
| --- | --- | --- |
| Kantra rules (5, target `quarkus3`) | `rgctl -f json rules run rules --target quarkus3` | `violations: []` |
| Kantra at discover time | `rgctl discover … --with-kantra --kantra-rules rules` | `0 VIOLATES edges` |
| No `javax` imports | `grep -rn javax src/main/java src/main/resources` | 0 |
| No vendor APIs | grep `weblogic\|java:jboss\|java:/topic\|InitialContext\|PortableRemoteObject` | 0 |
| Package layering | import-graph analysis (Python) | acyclic: `rest → service → {audit, model, utils}`, `audit → model`, `utils → model` |
| Graph re-index | `rgctl discover …` (77 files, 4,490 edges) | 356 functions, 171 classes, 3 interfaces indexed |
| No dangling references | grep for deleted class names | only historical mentions in Javadoc/tests |
| Brace/paren balance of all 43 Java files | script (string-aware) | balanced |
| Workflow files untouched | `git status .github` | unchanged |
| Blocked-step bookkeeping | `tasks.md` / `UserStory.md` | 34 subtasks + 12 ACs left open with reasons |

### Not verifiable here (toolchain absent)

| Check | Blocking tool | How to run |
| --- | --- | --- |
| `mvn clean verify` (compile + unit + IT) | JDK 21 + Maven | `make verify` |
| Boot + `/q/health/ready` 200 | JDK | `make run`, then `curl localhost:8080/q/health/ready` |
| Image build / non-root / scan | Podman/Buildah, Trivy | `make image`, `scripts/container-smoke.sh`, `make scan` |
| OpenShift deploy / probes / rollback | `oc` | `oc apply -k deploy/`, `docs/runbook.md` §3, §7 |
| Characterization diff vs monolith | running monolith | replay `CartEndpointIT`/`ProductEndpointIT` against `ROOT.war` |
| p95 / boot-time comparison | load generator | `docs/runbook.md` §6 |
| Coverage thresholds | Maven + JaCoCo | add `jacoco-maven-plugin` when the build runs |

---

## Notable engineering decisions

1. **Sequence, not identity.** The existing schema has `create sequence hibernate_sequence` and plain `int8` PKs;
   `IDENTITY` would produce inserts that violate the DDL. `allocationSize = 1` is mandatory because Hibernate 6
   defaults to 50.
2. **Cart state is explicit.** A `@Stateful` EJB plus `@SessionScoped` resource became `CartStore` +
   `@ApplicationScoped` service keyed by `cartId`, with TTL eviction and a documented Redis path. The legacy
   implementation silently shared carts across bean instances; the port fixes that without changing the API.
3. **Two messaging channels, one address.** This keeps the JMS *topic* fan-out (order persistence and low-stock
   notification) instead of collapsing it into a single consumer or a queue.
4. **Vendored Maven repository instead of `install:install-file`.** `lib/maven-repo` is a real `file://`
   repository, so `mvn verify` works from a clean checkout offline and still has no `systemPath`.
5. **Audit emission is new behaviour.** The monolith created a file logger and never logged; the migration wires
   real events, so this is a functional addition agreed in the plan (story 9), not a pure port.
6. **Frontend is served, not rewritten.** AngularJS/PatternFly stay untouched under `META-INF/resources`; only
   the two config files became server-rendered so no environment URL is baked into the image.

---

## Deliberate deviations from the plan

| Plan item | Deviation | Reason |
| --- | --- | --- |
| Task 3.1 "use `GenerationType.IDENTITY` or a sequence" | Sequence chosen | `IDENTITY` incompatible with the existing DDL |
| Task 7.2 `set(tmpId)` transfer | Copies the item list instead of aliasing | Legacy aliasing corrupts carts after login; behaviour for the SPA is identical |
| Task 9.2 `GET /products` with `@Path("/")` | Method-level `@Path("/")` removed | Quarkus REST would bind `/services/products/`; the SPA calls `/services/products` |
| Task 1.2 `quarkus-oidc` (decision-gated) | Not added | No backend authorization existed in the monolith; SSO stays frontend-only |
| Task 8.4 low-stock consumer | Re-implemented as a second channel | Preserves the (dead) MDB's documented rule rather than dropping it |
| Task 11.7 browser E2E | Static-asset smoke tests only | No browser/Playwright runtime; API journey covered by curl commands in the runbook |
| Task 14.4 "no new cycles" | Function-level SCCs 9 → 22, package graph acyclic | Index now includes test sources (50 → 77 files) and the new service↔audit wiring; no package-level cycle exists |
| 12.6/12.7 container tests | `scripts/container-smoke.sh` instead of Testcontainers-in-JUnit | The image cannot be built in this environment; a shell smoke test is the runnable equivalent in CI |

---

## Next steps (in order)

1. **Run the build**: `mvn -B clean verify` on JDK 21 (expect the first run to reveal any API drift in the
   Quarkus 3.15 surface). Fix compile errors, then re-run until green.
2. **Boot locally**: `mvn quarkus:dev`, verify `/q/health/ready`, `/services/products` (9 rows) and the SPA at
   `/index.html`.
3. **Container**: `make image && ./scripts/container-smoke.sh coolstore:$(git rev-parse --short HEAD)`, then
   `make scan`.
4. **Deploy**: create the two Secrets, adjust `deploy/configmap.yaml`, `oc apply -k deploy/`, run the runbook
   smoke tests; verify probes and the read-only filesystem under the `restricted-v2` SCC.
5. **Characterisation diff**: capture the monolith responses for the 9 route methods and diff status + JSON
   against the Quarkus service (normalise the random customer name/email).
6. **Load baseline**: p95 for `GET /services/products` and checkout POST, boot time, memory; compare ±10 %.
7. **Cutover + rollback rehearsal** in a scratch namespace, following `docs/runbook.md` §7.
8. **Add JaCoCo** to enforce the coverage targets (`service`/`utils` ≥ 70 %, `rest` ≥ 80 %) once the build runs.
9. **Technical debt backlog**: Redis `CartStore` for multi-replica, idempotent order consumption
   (`correlation_id` unique constraint), AngularJS rewrite, backend OIDC if roles become required.

---

## Detailed logs

Full timeline, blast-radius measurements, tool constraints and per-step notes:
`mig-execute-workspace/execution-log.md`.
