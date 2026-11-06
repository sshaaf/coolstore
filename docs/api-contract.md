# REST API contract

Base path: **`/services`** (`quarkus.rest.path=/services`, replacing `@ApplicationPath("/services")`).
All responses are `application/json`. The AngularJS SPA calls these URLs relatively
(`coolstore.json` has `MONOLITH=true`), so paths must not change.

## Endpoint inventory

| # | Method | Path | Request body | Success | Notes |
| - | ------ | ---- | ------------ | ------- | ----- |
| 1 | GET | `/services/products` | - | `200` `Product[]` | 9 seeded products |
| 2 | GET | `/services/products/{itemId}` | - | `200` `Product` / `204` | `204` for an unknown SKU (the monolith returned `null`) |
| 3 | GET | `/services/orders` | - | `200` `Order[]` | |
| 4 | GET | `/services/orders/{orderId}` | - | `200` `Order` / `204` | unknown id -> `204` |
| 5 | GET | `/services/cart/{cartId}` | - | `200` `ShoppingCart` | creates an empty cart on first access |
| 6 | POST | `/services/cart/{cartId}/{itemId}/{quantity}` | - | `200` `ShoppingCart` | merges duplicate lines, re-prices |
| 7 | DELETE | `/services/cart/{cartId}/{itemId}/{quantity}` | - | `200` `ShoppingCart` | see delete semantics |
| 8 | POST | `/services/cart/checkout/{cartId}` | - | `200` `ShoppingCart` | publishes the order event, clears items |
| 9 | POST | `/services/cart/{cartId}/{tmpId}` | - | `200` `ShoppingCart` | transfers the anonymous cart |

Nine operations across three resources; the plan's "8 endpoint methods" counts `POST /cart/{cartId}/{tmpId}`
together with the item routes.

### Routing overlap (intentional)

`POST /cart/checkout/{cartId}` and `POST /cart/{cartId}/{tmpId}` both have two path segments after `/cart`.
JAX-RS ranks the **literal** segment `checkout` above the template `{cartId}`, so `/cart/checkout/abc` is
always the checkout operation. `POST|DELETE /cart/{cartId}/{itemId}/{quantity}` has three segments and cannot
collide. A routing test (`CartEndpointIT`) covers this; the URLs are **not** to be "cleaned up" - the SPA's
`app/services/cart.js` depends on them.

## Payloads

### Product

```json
{ "itemId": "329299", "name": "Quarkus T-shirt", "desc": "", "price": 10.0,
  "location": "Raleigh", "quantity": 736, "link": "http://maps.google.com/?q=Raleigh" }
```

### ShoppingCart

```json
{ "cartItemTotal": 22.5, "cartItemPromoSavings": -7.5, "shippingTotal": 2.99,
  "shippingPromoSavings": 0.0, "cartTotal": 25.49,
  "shoppingCartItemList": [
    { "price": 7.5, "quantity": 3, "promoSavings": -2.5,
      "product": { "itemId": "329299", "name": "Quarkus T-shirt", "price": 10.0, "...": "..." } }
  ] }
```

Field names are load-bearing: the SPA binds `shoppingCartItemList`, `cartTotal`, `cartItemTotal`,
`shippingTotal`, `cartItemPromoSavings`, `shippingPromoSavings`, `promoSavings`, `quantity`, `price`,
`product.itemId`.

### Order

```json
{ "orderId": 1, "customerName": "Sven Karlsson", "customerEmail": "sven@gmail.com",
  "orderValue": 25.49, "retailPrice": 30.0, "discount": -7.5, "shippingFee": 2.99,
  "shippingDiscount": 0.0,
  "itemList": [ { "productId": "329299", "quantity": 3 } ] }
```

`OrderItem.id` is `@JsonIgnore` so the payload matches the monolith exactly.

### Order event (broker)

Published by checkout on the `orders` address, consumed by two channels:

```json
{ "orderValue": 25.49, "customerName": "Sven Karlsson", "customerEmail": "sven@gmail.com",
  "retailPrice": 30.0, "discount": -7.5, "shippingFee": 2.99, "shippingDiscount": 0.0,
  "items": [ { "productSku": "329299", "quantity": 3 } ] }
```

Key order and double formatting (`25.49`, `30.0`) are part of the contract.

## Cart semantics

| Operation | Behaviour |
| --- | --- |
| add | appends a line, prices the cart, merges lines with the same `product.itemId` (quantities summed, current catalogue price applied) |
| delete | `quantity >= line quantity` removes the line, otherwise decrements the line quantity; re-prices |
| set (transfer) | replaces the target cart's lines with a **copy** of the source cart's lines; re-prices |
| checkout | publishes one order event, then empties the cart and re-prices (totals become 0) |

Unknown `itemId` on add -> `500` with a stable JSON body:

```json
{ "error": "IllegalArgumentException", "message": "Unknown itemId: no-such-sku" }
```

## Pricing rules (must match to the cent)

- item promotion: SKU `329299`, 25% off -> `price = price * 0.75`, `promoSavings = -price * 0.25`
- free shipping when `cartItemTotal >= 75` -> `shippingTotal = 0`, `shippingPromoSavings = -oldShippingTotal`
- shipping bands: `[0,25) 2.99`, `[25,50) 4.99`, `[50,75) 6.99`, `[75,100) 8.99`, `[100,10000) 10.99`, otherwise `0`
- insurance (added when `cartItemTotal >= 25`): `[25,100) 2%`, `[100,500) 1.5%`, `[500,10000) 1%`,
  `HALF_UP` to 2 decimals
- `cartTotal = cartItemTotal + shippingTotal`

## Other HTTP surface

| Path | Purpose |
| --- | --- |
| `/q/health/live`, `/q/health/ready` | SmallRye Health (readiness includes the database check); replaces `health.jsp` |
| `/q/metrics` | Prometheus metrics |
| `/q/openapi` | OpenAPI document (smallrye-openapi) |
| `/index.html`, `/app/*`, `/partials/*`, `/bower_components/*` | AngularJS storefront |
| `/coolstore.json`, `/keycloak.json` | generated from configuration (`StorefrontConfigRoutes`) |
