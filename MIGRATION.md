# Migration: JBoss EAP 7.4 / Java EE 7 → Quarkus 3.27 (LTS) / Java 21 / Jakarta EE 10

The CoolStore monolith was ported in place. There is **no** microservice decomposition, no schema
change and no REST contract change.

## What changed

| Area | Before (JBoss EAP 7.4) | After (Quarkus 3.27.6, Java 21) |
|---|---|---|
| Build | `war`, `maven-war-plugin`, `finalName ROOT`, Java 1.8, `maven.test.skip=true` | `jar` (fast-jar), Quarkus BOM + `quarkus-maven-plugin`, `maven.compiler.release=21`, tests always run |
| Namespaces | `javax.*` (EJB 3.2, JPA 2.1, JAX-RS 2.0, CDI 1.1, JMS 2.0, JSON-P, JAXB) | `jakarta.*` (Jakarta EE 10); JAXB annotation dropped (Jackson) |
| Beans | `@Stateless`/`@Stateful`/`@MessageDriven` EJBs | `@ApplicationScoped` CDI beans + explicit `@Transactional` |
| Shipping | remote EJB lookup `ejb:/ROOT/ShippingService!…` on **every** pricing pass | `@Inject ShippingService` (two JNDI round-trips per cart removed) |
| Cart state | `@Stateful` instance field + `@SessionScoped` JAX-RS resource | `CartStore` keyed by `cartId`, bounded + idle eviction (`docs/cart-state.md`) |
| Messaging | JMS 2.0 `topic/orders`, MDB `@MessageDriven` | SmallRye Reactive Messaging: `Emitter` on `orders-out`, `@Incoming("orders")` on `OrderConsumer`, Kafka topic `orders`, group `coolstore-order-service` |
| Persistence | `persistence.xml`, `CoolstoreDS` JNDI, Hibernate 5 | `application.properties` + Agroal + Hibernate ORM 6, `generation=none` |
| Schema | Flyway 4.1.2 from an EJB `@Singleton @Startup` | `quarkus-flyway`, `migrate-at-start=true`, migrations **byte-identical** |
| Audit | `audit-logging-library` 1.0.0 `system`-scoped jar, `FileSystemAuditLogger`, never emitted | 2.0.0 normal dependency, `StreamableAuditLogger` (NDJSON/TCP), `logEventAsync` on `OrderService.save` |
| Web | `index.jsp` (+`request.getSession(true)`), `health.jsp`, `WEB-INF/web.xml`, `beans.xml` | static `index.html` in `META-INF/resources`, SmallRye Health `/q/health`, no descriptors |
| Config | `keycloak.json`/`coolstore.json` static with `http://localhost:8081` | generated at runtime from config at the same URLs (`ConfigEndpoint`, `SpaConfigRoutes`) |
| Packaging | WAR on EAP | `Containerfile` (UBI 9 / OpenJDK 21, UID 185) + Kustomize manifests for OpenShift |
| Vendored dead code | `weblogic/**`, `StartupListener`, `NonCatalogLogger`, `InventoryNotificationMDB` | deleted (`docs/decisions/`) |

## Deliberately unchanged

* the nine JAX-RS endpoint methods, their paths, media types and JSON shapes;
* the checkout document (`orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`,
  `shippingFee`, `shippingDiscount`, `items[].productSku`, `items[].quantity`) — pinned by golden tests;
* the shipping (2.99/4.99/6.99/8.99/10.99) and insurance (2 %/1.5 %/1 %, HALF_UP scale 2) tables;
* the promotion rules (25 % off `329299`, free shipping from 75);
* the PostgreSQL DDL, the `FK2BFF474F65938E8` constraint and the Flyway checksums;
* the AngularJS 1.x SPA and its `bower_components` tree (accepted EOL risk).

## Known behaviour changes (each one approved-by-design, documented)

1. **Audit events are now emitted.** The EAP code built a logger but never called it. `OrderService.save`
   emits `ORDER_CREATED` asynchronously; a failing collector cannot fail a checkout.
2. **Cart state is keyed by `cartId`** instead of being one in-memory cart per HTTP session/bean
   instance. This fixes a latent clustering bug; it also means carts do not survive a pod restart —
   sticky routing is configured on the Route until a distributed store is introduced.
3. **Duplicate order deliveries no longer double-decrement inventory** (per-replica hash guard).
4. **`/services/**` stays unauthenticated** unless `OIDC_ENABLED=true`; protecting it is a
   behaviour change that needs product sign-off.
5. `/health.jsp` is gone; use `/q/health` (`live`, `ready`, `started`).

## Risks accepted

| Risk | Mitigation / follow-up |
|---|---|
| AngularJS 1.x + `bower_components` EOL (CVE/image size) | Static assets only; front-end rewrite epic not in scope |
| Cart state is in-memory per replica | `haproxy.router.openshift.io/balance: source`; `CartStore` is replaceable by Infinispan/Redis |
| Duplicate guard is per replica | Durable message-id store as follow-up; at-least-once semantics preserved |
| Deprecated `logEvent` still present in 2.0.0 | Only `logEventAsync` is used |
| Audit collector may not exist in prod | `AUDIT_ENABLED=false` or `logEventAsyncSafe` + `exceptionally`; documented in `docs/audit.md` |
| Backend OIDC disabled by default | Sign-off + client-credentials path required before enabling |

## Rollback

The database is shared and unchanged (no new migration was added), so rollback is **code only**:

1. `oc apply -k deploy/overlays/prod` after pinning the image back to the previous digest, **or**
2. put the JBoss EAP route back in front and `deploy ./target/ROOT.war` on the 7.4 cluster (keep the
   WAR deployable for 30 days), with the CLI steps from the old README (documented in
   `docs/openshift.md`).

## Obsolete artifacts

`.github/workflows/main_clustertesting(dev).yml` (Java 11 + Azure Web App) is superseded by the
platform-provided OpenShift Pipelines (Tekton) pipeline. It is intentionally **left untouched** —
workflow files are owned by the migration platform. The `jboss-cli` data-source/JMS-topic setup
steps in the old README are obsolete and removed.
