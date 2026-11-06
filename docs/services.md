# Service and messaging model (Group 5)

## EJB → CDI

| Class | Before | After |
|---|---|---|
| `CatalogService` | `@Stateless` | `@ApplicationScoped` + `@Transactional` on read/write methods |
| `ProductService` | `@Stateless` | `@ApplicationScoped` |
| `OrderService` | `@Stateless` | `@ApplicationScoped` + `@Transactional` |
| `PromoService` | `@ApplicationScoped` | unchanged |
| `ShippingService` | `@Stateless @Remote` + `ShippingServiceRemote` | `@ApplicationScoped`, no remote interface |
| `ShoppingCartService` | `@Stateful` (instance cart) | `@ApplicationScoped` + `CartStore` |
| `ShoppingCartOrderProcessor` | `@Stateless` + `JMSContext`/`@Resource Topic` | `@ApplicationScoped` + `OrderPublisher` (`Emitter<String>`) |
| `OrderServiceMDB` | `@MessageDriven(topic/orders)` | `OrderConsumer.consume` `@Incoming("orders")` |
| `InventoryNotificationMDB` | dead WebLogic JNDI client | deleted (`docs/decisions/0001-…`) |

Method signatures are unchanged, so the call graph (`CartEndpoint → ShoppingCartService →
ProductService/PromoService/ShippingService`) still holds.

## Remote lookup removal

`ShoppingCartService.lookupShippingServiceRemote()` opened a `WildFlyInitialContextFactory` context and
looked up `ejb:/ROOT/ShippingService!…` **twice per pricing pass** (shipping + insurance). It is now a
plain `@Inject ShippingService`. This removes a `NamingException` failure path and a latency source.

## Messaging contract

| Item | Before | After |
|---|---|---|
| Destination | JMS topic `topic/orders` | Kafka topic `orders` (AMQ Streams) |
| Producer | `JMSContext.createProducer().send(ordersTopic, json)` | `Emitter<String>` on channel `orders-out` |
| Consumer | `@MessageDriven` `TextMessage` | `@Incoming("orders")` `String` |
| Consumer group | EAP cluster (competing consumers) | `coolstore-order-service` |
| Payload | `Transformers.shoppingCartToJson` | identical (golden-tested) |
| Ack | Auto-acknowledge | at-least-once; `failure-strategy=ignore` for poison messages |

SmallRye forbids one channel being both an emitter and a consumer, hence `orders-out` (producer) and
`orders` (consumer) mapping to the same topic. `%dev`/`%test` use the in-memory connector, so no broker
is needed for tests.

## Duplicate delivery

`OrderMessageDeduplicator` keeps a bounded LRU of SHA-256 payload digests: a redelivery on the same
replica is skipped, so inventory is not decremented twice. The guard is per replica and in-memory —
a duplicate landing on another replica after a restart would still be processed; closing that gap
needs a durable message-id store (tracked as follow-up). At-least-once delivery is preserved.
