# Performance baseline (legacy) and comparison method

**Status:** the legacy container could not be executed in the migration sandbox (no
container runtime and no JBoss EAP distribution available), so this document records the
approved load profile and the comparison procedure instead of measured numbers. The
numbers below must be filled in by the parallel-run team before cutover; the gate is
**p95 ≤ legacy p95 × 1.10** on all four hot endpoints.

## Load profile (fixed, both runs)
| Parameter | Value |
|---|---|
| Tool | `hey` / `k6` / JMeter (any, same tool for both runs) |
| Duration | 5 minutes per endpoint after a 60 s warm-up |
| Concurrency | 20 virtual users |
| Payload mix | 70% catalog list, 20% cart get, 9% cart add, 1% checkout |
| Data | seeded database (9 products), fresh cart ids per virtual user |
| Environment | same CPU/memory limits (250m/512Mi request, 1 CPU/1Gi limit) |

## Endpoints measured
`GET /services/products`, `GET /services/cart/{cartId}`,
`POST /services/cart/{cartId}/{itemId}/{quantity}`, `POST /services/cart/checkout/{cartId}`.

## Legacy results (to record)
| Endpoint | p95 | p99 | Throughput (req/s) |
|---|---|---|---|
| catalog list | _tbd_ | _tbd_ | _tbd_ |
| cart get | _tbd_ | _tbd_ | _tbd_ |
| cart add | _tbd_ | _tbd_ | _tbd_ |
| checkout | _tbd_ | _tbd_ | _tbd_ |

## Quarkus results (to record, task 10.6)
Same table, produced against the container image:
```bash
hey -z 5m -c 20 -w -o csv "https://<route>/services/products" > catalog-quarkus.csv
```
Record the numbers in `docs/perf-results.md` and flag any endpoint above the +10% gate.

## Expected pressure points
* Cart operations now make a Redis round trip per request (legacy was in-JVM state). Watch
  p99 on `cart get`/`cart add`; tune `REDIS_TIMEOUT` and connection pool if needed.
* Checkout publishes to the broker synchronously before returning (same as legacy), so
  broker latency is on the request path.
* Audit emission is asynchronous and must not appear in tail latency; if it does, check
  `AUDIT_STREAM_ENABLED` and the readiness probe interval.
