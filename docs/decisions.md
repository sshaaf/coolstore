# Architecture decisions (migration)

Each decision records the choice, the alternative that was rejected, and the impact on the
externally observable contract.

## D1 - Target runtime: Quarkus 3.40.x LTS on Java 21, single container
Chosen from the in-repo Konveyor profile (`quarkus3`, `jakarta-ee`) and because
`audit-logging-library:2.0.0` requires Java 21 (records + virtual threads).
*Impact:* none on the HTTP/JMS contracts.

## D2 - Cart state externalized to Redis (`cart:{cartId}`, TTL 24h)
`@Stateful` + `@SessionScoped` + the JSP-forced HTTP session have no Quarkus REST
equivalent and block HPA/zero-downtime. Redis keeps the API contract: the cart is still
identified by the `{cartId}` path parameter, so the SPA needs no change.
*Rejected:* client-side cart payload (changes the API contract), sticky sessions (blocks
scaling). *Degradation:* missing/expired key → empty cart, HTTP 200; Redis outage →
`CartStoreException` → HTTP 500 with a correlated log line. Tunable via `CART_TTL_SECONDS`
and `CART_KEY_PREFIX`.

## D3 - Messaging keeps JMS semantics on Artemis, destination `topic/orders`
`quarkus-artemis-jms` (Quarkiverse) keeps the `JMSContext`/`MessageListener` programming
model, so the message body (`Transformers.shoppingCartToJson`) and destination stay
wire-compatible with external consumers.
*Rejected:* Reactive Messaging + AMQP (would change the broker protocol/contract).
Ack semantics **changed deliberately**: `CLIENT_ACKNOWLEDGE`, acknowledged only after the
order is persisted and inventory deducted (legacy MDB used auto-ack). Retries are bounded
by the broker's `max-delivery-attempts`; exhausted messages go to `orders.dlq`.

## D4 - `InventoryNotificationMDB` deleted
It used a WebLogic T3/JNDI client (`t3://localhost:7001`) and had no activation annotation,
so it never ran. Low-inventory alerting is now an operational concern: the deployment
ships an Artemis address setting (`max-delivery-attempts`, DLA `orders.dlq`) and the
inventory level is observable through `/services/products` and metrics. If alerting is
needed, add it as a second consumer of `topic/orders` with `LOW_THRESHOLD = 50` - the
transformer to build such a consumer already exists.

## D5 - Audit logging v2: TCP streaming, record events, non-blocking
`StreamableAuditLogger` + 14-component `AuditEvent` record, emitted with `logEventAsync`.
`isReady()` gates emission and is probed at most once per `AUDIT_STREAM_RECONNECT_MS`, so a
dead collector neither blocks nor fails checkout. `AUDIT_STREAM_ENABLED=false` (the
default outside production) makes the adapter a no-op.

## D6 - No backend authorization (parity with legacy)
The legacy app only enforced login in the AngularJS SPA; the REST API was anonymous.
`quarkus-oidc` is wired (`OIDC_ENABLED`, realm `eap`, client `eap-app`) but no path is
protected. **Known gap** - adding backend enforcement is a separate, contract-changing
decision.

## D7 - Flyway owns the schema; Hibernate never generates DDL
`quarkus.flyway.migrate-at-start=true`, `baseline-on-migrate=true`,
`baseline-version=1.1` reproduces the legacy `baseline()` → `migrate()` ordering. Entity
sequence generators are pinned to the existing `hibernate_sequence` with
`allocationSize=1` because Hibernate 6 would otherwise create per-entity sequences that the
Flyway schema does not contain.

## D8 - Vendored audit jar resolved through a file-based Maven repository
`lib/maven-repo` is a real Maven repository layout (`com/enterprise/audit-logging-library/2.0.0`)
declared in the POM, replacing the unsupported `system` scope. Publishing to the internal
Nexus and dropping the `<repository>` entry is the production follow-up; the build works
offline today.

## D9 - JSON-P → Jackson, with the wire format frozen
`Transformers` uses Jackson `ObjectNode` so field names **and order** stay identical
(`orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`, `shippingFee`,
`shippingDiscount`, `items[].productSku`, `items[].quantity`). Golden fixtures under
`src/test/resources/golden/` are the gate.

## D10 - Error mapping keeps legacy status codes
Uncaught exceptions still return HTTP 500 (they used to escape to the container); the new
`ExceptionMapper`s only add structured JSON bodies and correlation ids. A null entity
(e.g. unknown product) still yields 204 No Content.
