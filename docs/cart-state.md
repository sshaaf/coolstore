# Cart state model

`ShoppingCartService` previously held `private ShoppingCart cart = new ShoppingCart()` in a
`@Stateful` bean, and `CartEndpoint` was `@SessionScoped`. Consequences on JBoss EAP: the cart was
per-JVM (broken across the two cluster nodes) and the JAX-RS resource relied on HTTP-session scoping,
which Quarkus REST does not support.

## Chosen model

`CartStore` (`@ApplicationScoped`) keeps `cartId → ShoppingCart` in a `ConcurrentHashMap`:

* `getOrCreate(cartId)` — used by `ShoppingCartService.getShoppingCart`;
* bounded by `cart.store.max-carts` (default 10 000) with idle eviction after
  `cart.store.idle-timeout` (default `PT4H`);
* the key is the `cartId` the SPA already sends on every REST call (`app/services/cart.js` keeps it in
  `localStorage`, and uses `userInfo.sub` after login), so `cartId`/`tmpId` merge semantics on
  `POST /services/cart/{cartId}/{tmpId}` are unchanged.

## Consistency semantics

* Within a replica: strongly consistent, cart survives between requests and is isolated per `cartId`
  (`PricingRulesTest.cartsAreIsolatedByCartId`).
* Across replicas: each replica has its own map. The Route sets
  `haproxy.router.openshift.io/balance: source` (sticky by client) for the transition period.
* Across a restart/rolling update: carts are **not** retained. Acceptable for a storefront cart; the
  SPA re-creates one on the next request.

## Follow-up

Replace `CartStore` with an Infinispan/Redis-backed implementation (same three methods) to remove the
sticky-session requirement. The class was deliberately kept free of JPA/session coupling so this is a
drop-in change.
