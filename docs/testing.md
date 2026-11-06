# Test strategy and coverage

## Layers
| Layer | Where | Runs in | Needs |
|---|---|---|---|
| Unit / characterization | `src/test/java/com/redhat/coolstore/**/*Test.java` | `mvn test` (surefire) | nothing (no container) |
| Contract fixtures | `src/test/resources/golden/` | replay in `CatalogContractIT` | stack |
| Integration / E2E | `.../it/*IT.java` | `mvn verify -Pintegration` (failsafe) | PostgreSQL, Redis, Artemis (Dev Services or explicit config) |

## Unit suite (executed, green)
67 tests / 0 failures covering:
* `ShippingService` - every bracket boundary (0/25/50/75/100/10000) and insurance tiers with
  HALF_UP rounding.
* `PromoService` - 25% off `329299`, free shipping ≥ 75, defensive promotion copy.
* `ShoppingCartService` - pricing totals, idempotent re-pricing, add/delete/dedupe/copy,
  checkout (publish → empty → persist), unknown product.
* `Transformers` - exact JSON field names/order, round-trip, unknown-field tolerance.
* `CartStore` - Redis key namespacing/TTL, decode failures, outage mapping.
* Messaging - producer body + broker failure, consumer ack-after-success, poison payload,
  non-text message.
* Audit - all 14 record components, defaults, NDJSON delivery to a stub TCP listener,
  collector outage, disabled mode.
* REST resources + exception mappers.

## Coverage (JaCoCo, unit layer)
Total line coverage **85.3%** (526/617): service 82.6%, utils 91.8%, model 89.2%,
rest 95.3%, audit 75.7%. The `verify` phase enforces **80% bundle line coverage**
(`-Djacoco.skip=true` for image builds).

## Integration suite (not executed in the sandbox)
`SmokeIT` (health/static assets/metrics), `CatalogContractIT` (golden diff, 204 on unknown
product), `CartFlowIT` (add → dedupe → price → checkout → order persisted; delete; 500 on
unknown product). They require a container runtime for Dev Services:
```bash
mvn verify -Pintegration
```
Without a runtime, point the app at real services:
```bash
DB_URL=jdbc:postgresql://... REDIS_HOSTS=redis://... ARTEMIS_URL=tcp://... \
  mvn verify -Pintegration
```

## Known gaps
* Resilience tests (DB/broker/Redis/collector down) are documented procedures
  (`docs/runbook.md`) rather than automated: they need the orchestrated stack.
* Multi-replica cart continuity and rolling-update tests need two pods + a Route.
* Performance comparison is a documented procedure (`docs/perf-baseline.md`); no legacy
  numbers could be captured in the sandbox.
* `audit` package coverage is 75.7% (config-building branches).
