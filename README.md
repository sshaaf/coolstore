# CoolStore Monolith (Quarkus)

The CoolStore storefront, migrated from a Java EE 7 application deployed on JBoss EAP 7.4 to
**Quarkus 3.20 (Jakarta EE 10) on Java 21**, packaged as a container image for **Red Hat OpenShift**.

The AngularJS single-page application is unchanged and is served from the same origin as the REST API,
so the browser keeps calling `/services/*` exactly as before.

| | Before | After |
|---|---|---|
| Runtime | JBoss EAP 7.4 (WAR, EJB container) | Quarkus 3.20 fast-jar (JVM) |
| Java | 8 source level (17 runtime) | 21 |
| APIs | Java EE 7 (`javax.*`) | Jakarta EE 10 (`jakarta.*`) |
| Persistence | JPA 2.1 + JNDI datasource + Flyway 4 bean | Hibernate ORM 6 + Agroal datasource + Quarkus Flyway |
| Messaging | JMS topic `topic/orders` + 2 MDBs | Kafka topic `orders` + 2 Reactive Messaging consumers |
| Remoting | `@Remote` EJB over WildFly JNDI | plain CDI injection |
| Audit | `audit-logging-library` 1.0.0 (file sink, unused) | `audit-logging-library` 2.0.0 (`StreamableAuditLogger`, TCP, async) |
| Deployment | manual `deploy ROOT.war` | `Containerfile` (UBI 9) + `deploy/openshift` (kustomize) |

Architecture and migration decisions are documented in
`mig-prompt-workspace/migration-prompt.md`; the plan and execution record live in
`mig-plan-workspace/` and `mig-execute-workspace/`.

## Prerequisites

- JDK 21 and Maven 3.9+ (build), or Podman/Docker (container build)
- PostgreSQL 15 (schema is created by Flyway)
- Kafka 3.x (AMQ Streams / Strimzi) for the order pipeline
- Optional: Keycloak for the SPA's SSO (`src/main/resources/META-INF/resources/keycloak.json`)

## Build and run

```bash
# unit + integration tests, then package the fast-jar
mvn verify

# dev mode (live reload) - needs PostgreSQL and Kafka reachable
mvn quarkus:dev

# run the packaged application
java -jar target/quarkus-app/quarkus-run.jar
```

### Local dependencies

```bash
podman run -d --name coolstore-db -p 5432:5432 \
  -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW \
  -e POSTGRES_DB=postgresDB postgres:15

# any single-node Kafka works; the topic "orders" is created on demand
podman run -d --name coolstore-kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 apache/kafka:3.8.0
```

Open <http://localhost:8080>.

The `audit-logging-library:2.0.0` artifact is not published to a reachable Maven repository, so it is
vendored in `lib/` and referenced with `system` scope (the same pattern the Java EE build used).

## Configuration

All settings are standard Quarkus properties and can be overridden with environment variables
(see `src/main/resources/application.properties`).

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `quarkus.datasource.jdbc.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/postgresDB` | PostgreSQL JDBC URL |
| `quarkus.datasource.username` | `DB_USER` | `postgresUser` | DB user |
| `quarkus.datasource.password` | `DB_PASSWORD` | `postgresPW` | DB password |
| `kafka.bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap |
| `audit.stream.host` | `AUDIT_STREAM_HOST` | `localhost` | Audit collector host |
| `audit.stream.port` | `AUDIT_STREAM_PORT` | `6514` | Audit collector TCP port |
| `audit.stream.enabled` | — | `true` | Set `false` to drop audit events |

`quarkus.flyway.migrate-at-start=true` applies `src/main/resources/db/migration` on boot with
`baseline-on-migrate`, so an existing EAP-era database is adopted without manual steps.

## API contract (unchanged)

| Method | Path | Notes |
|---|---|---|
| GET | `/services/products` | catalog list |
| GET | `/services/products/{itemId}` | single product (`204` when unknown — legacy behaviour) |
| GET | `/services/cart/{cartId}` | cart (empty cart for unknown ids) |
| POST | `/services/cart/{cartId}/{itemId}/{quantity}` | add item, dedupes by item id |
| DELETE | `/services/cart/{cartId}/{itemId}/{quantity}` | reduce/remove item |
| POST | `/services/cart/{cartId}/{tmpId}` | transfer a temporary cart into the user cart |
| POST | `/services/cart/checkout/{cartId}` | publish order, empty cart |
| GET | `/services/orders` | order history |
| GET | `/services/orders/{orderId}` | single order |

Operational endpoints: `/q/health`, `/q/health/live`, `/q/health/ready`, `/q/metrics`, `/q/openapi`.

### Messaging contract

Checkout publishes one JSON document to the Kafka topic `orders`:

```json
{"orderValue":10.49,"customerName":"Karl Svensson","customerEmail":"karl@gmail.com",
 "retailPrice":10.0,"discount":-2.5,"shippingFee":2.99,"shippingDiscount":0.0,
 "items":[{"productSku":"329299","quantity":1}]}
```

Two consumer groups subscribe to the same topic, preserving the fan-out of the former JMS topic:

- `coolstore-order-service` (`orders-in`) — persists the order, decrements inventory
- `coolstore-inventory-notification` (`orders-inventory`) — warns when projected stock drops below 50

## Testing

- `ShippingServiceTest`, `PromoServiceTest`, `TransformersTest` — pure unit tests pinning the pricing,
  shipping, promotion and wire-format behaviour of the legacy application
- `CartEndpointTest` — `@QuarkusTest` + RestAssured characterization tests for the HTTP contract
  (H2 in PostgreSQL mode, real Flyway seed data)
- `OrderMessageConsumerTest` — consumer behaviour: order persistence, inventory decrement, unknown item tolerance
- `AuditServiceTest` — audit 2.0.0 API usage and failure tolerance

The test profile uses an in-memory messaging connector, so no broker is required for `mvn verify`.

## Container

```bash
podman build -t quay.io/example/coolstore-monolith:1.0.0 .
podman run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host:5432/postgresDB -e DB_USER=postgresUser -e DB_PASSWORD=postgresPW \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  quay.io/example/coolstore-monolith:1.0.0
```

The image is a multi-stage UBI 9 build (`ubi9/openjdk-21` → `ubi9/openjdk-21-runtime`) and runs as
non-root UID 185 on port 8080.

## Deploy to OpenShift

```bash
oc new-project coolstore
# build the image with OpenShift (or push it to your registry first)
oc new-build --name coolstore-monolith --binary --strategy docker
oc start-build coolstore-monolith --from-dir=. --follow
oc apply -k deploy/openshift
oc get route coolstore-monolith -o jsonpath='{.spec.host}'
```

`deploy/openshift` contains the Deployment (2 replicas, rolling update, non-root, probes, limits),
Service, edge-TLS Route, ConfigMap, DB Secret, PostgreSQL deployment/PVC and the `orders` `KafkaTopic`
(requires the AMQ Streams/Strimzi operator).

### Rollout and rollback

```bash
oc rollout status deploy/coolstore-monolith
oc rollout undo deploy/coolstore-monolith        # back to the previous image
oc logs -f deploy/coolstore-monolith
```

Rollback to the pre-migration application is the previous EAP image plus `ROOT.war`; the database schema
is unchanged by this migration, so no data rollback is required.

## Migration notes and known follow-ups

- **Cart state** was held in a `@Stateful` EJB per HTTP session. It now lives in a `cartId`-keyed
  `CartStore` inside the application; the HTTP contract is identical, but with more than one replica a
  shopper needs session affinity until the store is moved to a shared cache (e.g. Redis).
- **JMS topic fan-out** is reproduced with two Kafka consumer groups. A single group would divide the
  orders between handlers and silently lose work.
- **Hibernate 6** changed default sequence naming; `Order`/`OrderItem` explicitly bind the legacy
  `hibernate_sequence` so the existing schema keeps working.
- **JSP is gone**: `index.jsp` became `index.html` (its only scriptlet opened a session), and
  `health.jsp` is replaced by SmallRye Health under `/q/health`.
- **Audit logging** is now real: `StreamableAuditLogger` streams `AuditEvent` records over TCP and
  never blocks checkout when the collector is unavailable.
- **Follow-ups**: migrate the AngularJS front end (EOL), replace the process-local cart store with Redis,
  publish `audit-logging-library` to an internal repository to drop `system` scope, and add a cluster-level
  end-to-end/performance test.

## Repository layout

```
src/main/java/com/redhat/coolstore/
  model/      JPA entities and DTOs
  rest/       JAX-RS resources (/services)
  service/    catalog, cart, pricing, shipping, messaging
  audit/      audit-logging-library 2.0.0 integration
  utils/      entity/DTO transformations and order JSON
src/main/resources/
  application.properties   runtime configuration
  db/migration/            Flyway schema + seed data (unchanged)
  META-INF/resources/      AngularJS SPA and vendored assets
src/test/java/             unit + @QuarkusTest characterization tests
deploy/openshift/          kustomize manifests
Containerfile              UBI 9 multi-stage build
lib/                       vendored audit-logging-library 2.0.0
```
