# ADR 001 - Keep asynchronous order processing, move from JMS topic to AMQP 1.0

- **Status:** accepted
- **Context:** migration from Java EE 7 on JBoss EAP 7.4 to Quarkus 3 / Java 21
- **Supersedes:** `@MessageDriven` + `java:/topic/orders` (JMS 2.0, Artemis)

## Context

Checkout in the monolith publishes the priced cart to the JMS **topic** `topic/orders` and returns
immediately. Two consumers subscribe:

1. `OrderServiceMDB` - persists the `Order` (with items) and decrements inventory per line.
2. `InventoryNotificationMDB` - warns when the projected stock drops below 50. It was **dead code**:
   it implemented `MessageListener` without `@MessageDriven` and opened a WebLogic T3 connection.

The REST contract (`POST /services/cart/checkout/{cartId}` returns the emptied cart) depends on the
publish being fire-and-forget.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| **A. SmallRye Reactive Messaging + AMQP 1.0 connector (chosen)** | Same fan-out semantics; Artemis speaks AMQP 1.0; connector config is pure MicroProfile Config; health/metrics integrated | Requires a broker; at-least-once delivery |
| B. Kafka connector | Strong ecosystem, replay | New operational dependency, topic-per-consumer-group semantics differ from a JMS topic, more moving parts for a 1.8 KLOC app |
| C. In-process CDI event | Zero infrastructure | Loses durability and the asynchronous boundary; behaviour change |

## Decision

**Option A.** `ShoppingCartOrderProcessor` sends through `@Channel("orders") Emitter<String>`; the two
consumers are `@Incoming` methods:

| Channel | Address | Consumer |
| --- | --- | --- |
| `orders` | `orders` | `OrderEventConsumer` (persist order + decrement inventory) |
| `orders-inventory` | `orders` | `InventoryNotificationConsumer` (low-stock warning, threshold 50) |

Two channels pointing at the same AMQP address reproduce the topic fan-out: each channel is an
independent subscription, so both consumers see every order event. The payload is byte-compatible
with the JMS `TextMessage` body (`Transformers.shoppingCartToJson`).

## Consequences

- Delivery is **at-least-once**. `OrderService.save` is not idempotent today; a redelivery creates a
  second order row. Accepted for the demo, documented in `docs/design-notes.md`; the mitigation is an
  `orderKey` (correlation id) unique constraint when the audit/correlation work is productised.
- Malformed payloads are logged and dropped (`OrderEventConsumer`), so a poison message cannot block
  the stream - a deliberate change from the MDB, which threw and caused redelivery.
- Tests run with `smallrye-in-memory` for the `orders` channel: no broker needed in CI.
- Broker credentials come from a Secret (`AMQP_PASSWORD`); the address/host are ConfigMap entries.
