# REST API parity matrix

Base path `/services` (`quarkus.rest.path`), preserving the legacy `@ApplicationPath`.
All responses are `application/json` unless noted.

| # | Verb | Path | Success | Legacy body | Notes |
|---|------|------|---------|-------------|-------|
| 1 | GET | `/services/products` | 200 | `Product[]` (9 seeded) | `itemId, name, desc, price, location, quantity, link` |
| 2 | GET | `/services/products/{itemId}` | 200 / **204** | `Product` / empty | null entity → 204 No Content |
| 3 | GET | `/services/cart/{cartId}` | 200 | `ShoppingCart` | missing/expired cart → empty cart |
| 4 | POST | `/services/cart/{cartId}/{itemId}/{quantity}` | 200 | `ShoppingCart` | add + re-price + dedupe; unknown product → 500 |
| 5 | POST | `/services/cart/{cartId}/{tmpId}` | 200 | `ShoppingCart` | 2-segment copy; disambiguated from #4 by segment count |
| 6 | DELETE | `/services/cart/{cartId}/{itemId}/{quantity}` | 200 | `ShoppingCart` | quantity ≥ line → line removed |
| 7 | POST | `/services/cart/checkout/{cartId}` | 200 | `ShoppingCart` | publish to `topic/orders`, then emptied + re-priced cart |
| 8 | GET | `/services/orders` | 200 | `Order[]` | |
| 9 | GET | `/services/orders/{orderId}` | 200 / 204 | `Order` / empty | |

Cart payload fields: `cartItemTotal`, `cartItemPromoSavings`, `shippingTotal`,
`shippingPromoSavings`, `cartTotal`, `shoppingCartItemList[]`
(`price`, `quantity`, `promoSavings`, `product{...}`).

Order payload fields: `orderId`, `customerName`, `customerEmail`, `orderValue`,
`retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `itemList[]`
(`productId`, `quantity`; `id` is internal).

JMS message (`topic/orders`) fields, in order: `orderValue`, `customerName`,
`customerEmail`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount`, `items[]`
(`productSku`, `quantity`).

## Deliberate, documented differences
| Area | Legacy | Now | Why |
|---|---|---|---|
| Error body | container HTML/empty | `{"error","message","status","correlationId"}` | observability; status codes unchanged |
| Cart lifetime | HTTP session (JSESSIONID) | Redis TTL 24h | restart/scaling survival |
| Broker ack | auto-acknowledge | ack after persist + inventory | no silent order loss |
| `/services/products/{unknown}` | 204 | 204 | unchanged (asserted) |

CORS: none required - the SPA is served same-origin from `META-INF/resources`.
