# CoolStore

CoolStore is a small storefront (AngularJS SPA) plus an order backend, migrated from a **Java EE 7 WAR on
JBoss EAP 7.4** to a **Quarkus 3 / Java 21** application that runs as a single container on **Red Hat
OpenShift**.

| | Before | After |
| --- | --- | --- |
| Runtime | JBoss EAP 7.4 (standalone-full-ha) | Quarkus 3.15, fast-jar |
| Java | 8 | 21 |
| APIs | Java EE 7 (`javax.*`) | Jakarta EE 10 (`jakarta.*`) |
| Build output | `ROOT.war` | `target/quarkus-app/quarkus-run.jar` |
| Business services | EJB 3.2 (`@Stateless`, `@Stateful`, `@MessageDriven`) | CDI (`@ApplicationScoped`, `@Transactional`, reactive messaging) |
| Datasource | JBoss JNDI `java:jboss/datasources/CoolstoreDS` | Quarkus datasource (`quarkus.datasource.*`) |
| Migrations | Flyway 4.1.2 in an EJB `@Singleton @Startup` | `quarkus-flyway` (Flyway 10, `migrate-at-start`) |
| Order events | JMS topic `topic/orders` | SmallRye Reactive Messaging over AMQP 1.0 |
| Shipping call | remote EJB (`ejb:/ROOT/ShippingService!...`) | direct CDI injection |
| Audit | `audit-logging-library` 1.0.0, `systemPath`, file logger, never emitted | 2.0.0, `StreamableAuditLogger` + `AuditEvent` record + `logEventAsync` |
| Health | `health.jsp` containing `1` | SmallRye Health `/q/health/live|ready` |
| Tests | none (`maven.test.skip=true`) | unit + `@QuarkusTest` + Testcontainers |
| Deployment | manual WAR deploy + JBoss CLI | `Containerfile` + `deploy/` manifests |

## Layout

```
src/main/java/com/redhat/coolstore
├── audit/     AuditService (TCP streaming), AuditEvents (record factory)
├── model/     JPA entities (CatalogItemEntity, InventoryEntity, Order, OrderItem) + DTOs
├── rest/      ProductEndpoint, OrderEndpoint, CartEndpoint, ApiExceptionMapper
├── service/   Catalog, Product, Order, Promo, Shipping, ShoppingCart, CartStore,
│              ShoppingCartOrderProcessor (Emitter), OrderEventConsumer, InventoryNotificationConsumer
└── utils/     Transformers (Jackson), StartupLifecycle, DatabaseHealthCheck, StorefrontConfigRoutes

src/main/resources
├── application.properties
├── db/migration/            V1_1__CreateSchema.sql, V1_2__AddInitialData.sql (unchanged)
└── META-INF/resources/      index.html, app/, partials/, bower_components/ (AngularJS SPA)
```

## Build and test

Requirements: **JDK 21**, **Maven 3.9+**, Docker or Podman (for the integration tests' PostgreSQL).

```bash
mvn -B clean verify      # unit + @QuarkusTest/Testcontainers, produces target/quarkus-app/
mvn -B quarkus:dev       # dev mode: Dev Services PostgreSQL, in-memory messaging
java -jar target/quarkus-app/quarkus-run.jar
```

The vendored `audit-logging-library` 2.0.0 is resolved from `lib/maven-repo` (a `file://` Maven repository
declared in `pom.xml`) - no `mvn install:install-file` step, no `<systemPath>`.

Useful Make targets: `make unit`, `make verify`, `make dev`, `make image`, `make scan`.

## Configuration

Everything environment-specific is MicroProfile Config with env-var overrides:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | *unset* (Dev Services in dev/test) | JDBC URL, e.g. `jdbc:postgresql://postgres:5432/coolstore` |
| `DB_USER` / `DB_PASSWORD` | `coolstore` | datasource credentials (Secret in OpenShift) |
| `AMQP_HOST` / `AMQP_PORT` | `localhost` / `5672` | Artemis broker |
| `AMQP_USER` / `AMQP_PASSWORD` | `admin` / `admin` | broker credentials (Secret) |
| `AUDIT_ENABLED`, `AUDIT_STREAM_*` | `true`, `localhost:6514 TCP` | audit streaming |
| `CART_TTL_SECONDS` | `1800` | idle cart eviction |
| `SSO_ENABLED`, `KEYCLOAK_AUTH_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_RESOURCE` | `true`, `http://localhost:8081/`, `eap`, `eap-app` | storefront SSO |
| `COOLSTORE_MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT` | `true`, empty, empty | SPA API routing |

Full reference: `docs/runbook.md`.

## HTTP surface

Base path `/services` (unchanged for the SPA):

| Method | Path |
| --- | --- |
| GET | `/services/products`, `/services/products/{itemId}` |
| GET | `/services/orders`, `/services/orders/{orderId}` |
| GET | `/services/cart/{cartId}` |
| POST | `/services/cart/{cartId}/{itemId}/{quantity}` |
| DELETE | `/services/cart/{cartId}/{itemId}/{quantity}` |
| POST | `/services/cart/{cartId}/{tmpId}` (cart transfer) |
| POST | `/services/cart/checkout/{cartId}` |

Plus `/q/health/*`, `/q/metrics`, `/q/openapi`, the SPA at `/index.html`, and the generated
`/coolstore.json` / `/keycloak.json`.

Contract details and payloads: `docs/api-contract.md`.

## Deploy to OpenShift

```bash
oc new-project coolstore
oc -n coolstore create secret generic coolstore-db --from-literal=QUARKUS_DATASOURCE_PASSWORD='...'
oc -n coolstore create secret generic coolstore-broker --from-literal=AMQP_PASSWORD='...'
oc apply -k deploy/
```

Manifests: Deployment (2 replicas, health probes, read-only root filesystem, non-root), Service, edge TLS
Route, ConfigMap, ServiceAccount, PodDisruptionBudget, HPA (2-4), NetworkPolicy. Secrets are created
out-of-band (`deploy/secret.example.yaml` is a template, never committed with values).

CI/CD is platform-managed (Tekton on OpenShift); this repository intentionally contains no workflow
definitions.

## Documentation

| Document | Contents |
| --- | --- |
| `docs/design-notes.md` | migration decisions: cart store, pricing pipeline, JPA mappings, JSON contract |
| `docs/adr/001-messaging.md` | why AMQP 1.0 reactive messaging replaced the JMS topic |
| `docs/ejb-to-cdi-map.md` | every legacy EJB/type and its replacement |
| `docs/audit-logging.md` | library 2.0.0 API, event schema, Konveyor rule closure |
| `docs/api-contract.md` | endpoints, payloads, pricing rules, status codes |
| `docs/runbook.md` | build, deploy, configure, scale, rollback, troubleshoot |
| `docs/testing.md` | test layers and coverage |
| `docs/vendor-lockin.md` | removed vendor APIs and outstanding technical debt |
| `mig-plan-workspace/` | migration plan, tasks and user stories |
| `mig-execute-workspace/` | execution report and log for this migration |

## Known limitations

- The AngularJS 1.x / PatternFly frontend is vendored unchanged (EOL, rewrite out of scope).
- The default `CartStore` is in-memory and per pod: use sticky sessions or a Redis-backed store when running
  more than one replica.
- Order event delivery is at-least-once and order persistence is not yet idempotent.
- Backend OIDC is not enabled; SSO stays frontend-only (as in the monolith).
