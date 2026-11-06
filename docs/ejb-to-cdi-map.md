# EJB to CDI mapping

| Legacy type | Kind | Replacement | Semantic notes |
| --- | --- | --- | --- |
| `service/CatalogService` | `@Stateless` | `@ApplicationScoped` + `@Transactional` | Criteria queries unchanged; `em.merge` for inventory updates; adds a null guard so an unknown SKU cannot NPE |
| `service/ProductService` | `@Stateless` | `@ApplicationScoped` | stateless mapping only |
| `service/OrderService` | `@Stateless` + `@PostConstruct`/`@PreDestroy` | `@ApplicationScoped` + `@Transactional` + CDI lifecycle | the audit logger moved to `audit/AuditService`; `save()` now emits `ORDER_SAVED` |
| `service/PromoService` | `@ApplicationScoped` + `Serializable` | `@ApplicationScoped` (POJO) | `Serializable`/`serialVersionUID` dropped; promotion set unchanged |
| `service/ShippingService` | `@Stateless @Remote` | `@ApplicationScoped` | remote interface `ShippingServiceRemote` deleted |
| `service/ShippingServiceRemote` | `@Remote` interface | *deleted* | callers inject `ShippingService` directly |
| `service/ShoppingCartService` | `@Stateful` | `@ApplicationScoped` + `CartStore` | state externalised and keyed by `cartId`; `lookupShippingServiceRemote()` replaced by injection |
| `service/ShoppingCartOrderProcessor` | `@Stateless` + `@Resource Topic` + `JMSContext` | `@ApplicationScoped` + `@Channel("orders") Emitter<String>` | see ADR 001 |
| `service/OrderServiceMDB` | `@MessageDriven` | `service/OrderEventConsumer` (`@Incoming("orders")`) | persistence + inventory decrement preserved; malformed payloads are logged instead of thrown |
| `service/InventoryNotificationMDB` | dead `MessageListener` + WebLogic JNDI | `service/InventoryNotificationConsumer` (`@Incoming("orders-inventory")`) | threshold 50 preserved; T3/JNDI code deleted |
| `utils/DataBaseMigrationStartup` | `@Singleton @Startup` | `quarkus-flyway.migrate-at-start` | Flyway 4.1.2 `new Flyway()` API removed |
| `utils/StartupListener` | WebLogic `ApplicationLifecycleListener` | `utils/StartupLifecycle` (`@Observes StartupEvent`/`ShutdownEvent`) | log markers `AppListener(postStart)`/`(preStop)` preserved |
| `utils/Producers` | `@Produces Logger` (JUL) | `org.jboss.logging.Logger` per class | Quarkus logging; per-class logger names preserved |
| `persistence/Resources` | `@Produces EntityManager` | *deleted* | Quarkus injects the `EntityManager` for the default PU |
| `rest/CartEndpoint` | `@SessionScoped` | `@ApplicationScoped` (stateless) | cart state moved to `CartStore`; `dedupeCartItems` moved to `ShoppingCartService` |
| `rest/ProductEndpoint`, `rest/OrderEndpoint` | `@RequestScoped` | `@ApplicationScoped` | no per-request state |
| `rest/RestApplication` | `@ApplicationPath("/services")` | *deleted* | `quarkus.rest.path=/services` |
| `weblogic.*` shims | vendor API | *deleted* | nothing replaces them; see `vendor-lockin.md` |

## Transaction semantics

| Legacy | Now |
| --- | --- |
| EJB container starts a transaction per business method | `@Transactional` on the write paths (`CatalogService.updateInventoryItems`, `OrderService.save/get*`) |
| `@TransactionManagement(BEAN)` on the Flyway migrator | Quarkus Flyway runs its own transactions at startup |
| JMS XA/`AUTO_ACKNOWLEDGE` | AMQP at-least-once; the consumer method is transactional through `OrderService.save` |

## Lifecycle and pooling

- EJB instances were pooled; `@ApplicationScoped` beans are singletons for the application lifetime. Every
  migrated bean is stateless (no mutable request state), which was verified by moving the only stateful bean
  (`ShoppingCartService`) onto an explicit store.
- `@PostConstruct`/`@PreDestroy` remain valid CDI callbacks (`OrderService` audit wiring now lives in
  `AuditService`).
- `@Startup` semantics come from `quarkus.flyway.migrate-at-start` and `@Observes StartupEvent`.
