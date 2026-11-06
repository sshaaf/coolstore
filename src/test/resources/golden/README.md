# Golden fixtures (contract freeze)

Recorded from the legacy `ROOT.war` deployment (Java EE 7 / JBoss EAP 7.4) against the
seed data in `db/migration/V1_2__AddInitialData.sql`.

| File | Endpoint | Notes |
|---|---|---|
| `products.json` | `GET /services/products` | 9 products, inventory flattened into the product |
| `cart-after-add-329299-x2.json` | `POST /services/cart/{cartId}/329299/2` | promotion applied, dedupe to one line |
| `order-message-2x329299.json` | `topic/orders` body | `customerName`/`customerEmail` are random by design |

`GET /services/products/{unknown}` returns **204 No Content** (null entity), matching the
legacy JAX-RS behaviour; the SPA relies on it.
