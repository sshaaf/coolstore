# Design notes - Java EE 7 monolith to Quarkus 3

## 1. Deployment unit

Remains a **single deployable**. The application is 1.8 KLOC with 8 REST endpoints; splitting it into
services would buy nothing and would multiply the operational surface. The `ShippingService` boundary is
kept as a CDI bean so it can be extracted later without touching callers.

## 2. Layering

```
rest  ->  service  ->  model
             |  \--------> utils (Transformers)
             \-----------> audit (AuditService, AuditEvents)
```

No package depends on `rest`. `persistence/Resources` (the `EntityManager` producer) is gone: Quarkus
provides the `EntityManager` for the single default persistence unit, so services inject it directly.

## 3. EJB to CDI

See `docs/ejb-to-cdi-map.md` for the per-class mapping. Summary of semantic changes:

| EJB feature | Replacement | Notes |
| --- | --- | --- |
| `@Stateless` pooling | `@ApplicationScoped` | Beans must be stateless/thread-safe; none hold request state |
| Container-managed transactions | `@Transactional` (Jakarta) on write paths | `SUPPORTS`-style reads are declared `@Transactional` for a stable session |
| `@Stateful` cart | `CartStore` keyed by `cartId` | see section 4 |
| `@Singleton @Startup` | `quarkus-flyway.migrate-at-start` | Flyway 10, no programmatic API |
| `@MessageDriven` | `@Incoming` | see ADR 001 |
| `@Remote` + JNDI | direct CDI injection | `ShippingServiceRemote` deleted |
| `@PostConstruct`/`@PreDestroy` | same annotations (CDI) | audit logger lifecycle |

## 4. Cart state

The monolith had a `@Stateful ShoppingCartService` with a single `cart` field, injected into a
`@SessionScoped` `CartEndpoint`. Requests that landed on another bean instance (or another node) silently
shared or lost carts. The migration makes the state explicit:

- `CartStore` - interface: `get`, `put`, `remove`, `size`.
- `InMemoryCartStore` - default; `ConcurrentHashMap<String, Entry>` with idle-time eviction
  (`cart.store.ttl-seconds`, default 1800s).
- Cart ids come from the SPA (`localStorage`), so the store, not the HTTP session, is the source of truth.
- **Multi-replica:** the in-memory store is per-pod. Either use sticky sessions or provide a Redis-backed
  `CartStore` (`quarkus-redis-client`) behind the same interface - no change to `ShoppingCartService`,
  `CartEndpoint` or the tests.
- `setCart(cartId, tmpId)` copies the item list instead of sharing the reference (the legacy code aliased
  the two carts' lists, which corrupted carts after a login transfer).

## 5. Pricing pipeline (unchanged, deliberately)

`priceShoppingCart` order of operations, preserved exactly:

1. `initShoppingCartForPricing` - zero all totals, re-read catalogue prices, reset `promoSavings`.
2. `applyCartItemPromotions` - 25% off SKU `329299`; `promoSavings` is negative.
3. Accumulate `cartItemTotal` and `cartItemPromoSavings` per line.
4. `shippingTotal = calculateShipping(cart)`; add `calculateShippingInsurance` when `cartItemTotal >= 25`.
5. `applyShippingPromotions` - free shipping when `cartItemTotal >= 75`, recording the waived fee as a
   negative `shippingPromoSavings`.
6. `cartTotal = cartItemTotal + shippingTotal`.

`dedupeCartItems` moved from `CartEndpoint` into `ShoppingCartService` (it re-reads catalogue prices and
merges quantities - business logic, not HTTP). Route handlers are now thin.

> Parity over prettiness: the inconsistent shipping bands (a 10,000+ cart gets **no** shipping fee) and the
> "free shipping from 75" rule were kept. Fixing them is a product decision, not a migration step.

## 6. JPA / Hibernate ORM 6 decisions

- `Order.orderId` and `OrderItem.id` use
  `@GeneratedValue(strategy = SEQUENCE, generator = "hibernate_sequence")` with
  `@SequenceGenerator(sequenceName = "hibernate_sequence", allocationSize = 1)`.
  The Flyway baseline creates `create sequence hibernate_sequence`, and the columns are plain `int8` with
  no identity definition, so `IDENTITY` would fail. `allocationSize = 1` is required: Hibernate 6 defaults
  to 50 and would ask the sequence for blocks it cannot serve.
- Removed `@Column(name = "TOTAL_PRICE")` from `Order` - no such column exists in the schema.
- `@OneToOne(fetch = EAGER)` + `@PrimaryKeyJoinColumn` (catalogue to inventory) and
  `@OneToMany(fetch = EAGER, cascade = ALL, orphanRemoval = true)` + `@JoinColumn(name = "ORDER_ID")`
  (order to items) are kept: both are part of the API payload.
- `quarkus.hibernate-orm.schema-management.strategy=none` - Flyway owns the schema.
- Dropped `hibernate.jdbc.use_get_generated_keys=false`, `hibernate.use_sql_comments` and
  `hibernate.format_sql` from the old `persistence.xml`: with sequence generation the generated-key hint is
  wrong in ORM 6 and the SQL-logging flags belong to `quarkus.hibernate-orm.log.sql`.

## 7. JSON contract

`Transformers` moved from JSON-P to Jackson. The wire format is identical:

- keys and their order: `orderValue`, `customerName`, `customerEmail`, `retailPrice`, `discount`,
  `shippingFee`, `shippingDiscount`, `items[]`
- item keys: `productSku`, `quantity`
- doubles serialise with a decimal point (`10.0`), like JSON-P did
- the demo random customer name/email is kept; a deterministic overload exists for tests

DTOs stay mutable POJOs with the original getter names because the SPA binds to them
(`shoppingCartItemList`, `cartTotal`, `cartItemTotal`, `shippingTotal`, `promoSavings`, ...).
`Serializable`, `serialVersionUID` and the `@Dependent` scope were dropped - the objects are no longer
passed through an EJB container or an HTTP session.

`OrderItem.getId()` is annotated `@JsonIgnore`: the legacy `OrderItem` never serialised its surrogate key,
so exposing it would change the `/services/orders` payload.

## 8. Frontend

- Static content moved from `src/main/webapp` to `src/main/resources/META-INF/resources` so Quarkus serves
  it at the same absolute paths (`/app/*`, `/partials/*`, `/bower_components/*`).
- `index.jsp` became `index.html`: the only JSP feature used was `request.getSession(true)`, which has no
  meaning now that the cart lives in `CartStore`.
- `coolstore.json` and `keycloak.json` are rendered from MicroProfile Config by `StorefrontConfigRoutes`
  (Vert.x `Router` observer). The SPA still fetches the same two URLs, but the image no longer bakes
  `localhost:8081` into `keycloak.json`.
- `bower_components` (AngularJS 1.x, PatternFly, jQuery) is vendored and untouched - explicitly out of
  scope, recorded as technical debt.
- Backend SSO is **not** enabled (`quarkus-oidc` not added): the monolith only used container-managed
  security declaratively in `web.xml`, with no roles enforced on the REST layer.

## 9. Configuration

All environment-specific values are MicroProfile Config keys with env-var overrides
(`DB_URL`, `DB_USER`, `DB_PASSWORD`, `AMQP_*`, `AUDIT_STREAM_*`, `SSO_ENABLED`, `KEYCLOAK_*`,
`CART_TTL_SECONDS`). OpenShift supplies them from a ConfigMap and two Secrets; nothing secret is in git.
