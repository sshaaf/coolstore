# Audit logging - library 1.0.0 to 2.0.0

## What the monolith did

`OrderService` built `new FileSystemAuditLogger(config)` with
`config.setLogDirectory("./device-inventory-audit-logs")`, then **never emitted an event**. The Konveyor
ruleset flagged the import (`audit-logging-0003`).

## What 2.0.0 provides (verified against `lib/maven-repo/.../audit-logging-library-2.0.0.jar`)

| Type | API used here |
| --- | --- |
| `com.enterprise.audit.logging.config.AuditConfiguration` | `setStreamHost/setStreamPort/setStreamProtocol/setStreamBufferSize/setStreamReconnectMs` (defaults `localhost`, `TCP`; property keys `audit.stream.*`) |
| `com.enterprise.audit.logging.model.AuditEvent` | **Java record** with 14 components |
| `com.enterprise.audit.logging.model.AuditResult` | enum `SUCCESS, FAILURE, DENIED, INVALID, TIMEOUT, CANCELLED, UNKNOWN` |
| `com.enterprise.audit.logging.service.StreamableAuditLogger` | `new StreamableAuditLogger(config)`, `logEventAsync(event)` -> `CompletableFuture<Void>`, `logEventAsyncSafe`, `isReady()`, `close()` |

`StreamableAuditLogger` writes one JSON document per TCP connection using a virtual-thread executor
(`Executors.newVirtualThreadPerTaskExecutor()`), which requires Java 21 - satisfied by the target runtime.
It needs Jackson (`jackson-databind` + `jackson-datatype-jsr310`), provided by `quarkus-rest-jackson`.

## AuditEvent record component order

```java
new AuditEvent(
    Instant timestamp,        // 1
    String event_type,        // 2
    String user_id,           // 3
    String session_id,        // 4
    String application,       // 5
    String component,         // 6
    String action,            // 7
    String resource,          // 8
    AuditResult result,       // 9
    String message,           // 10
    Map<String, Object> details, // 11
    String correlation_id,    // 12
    String source_ip,         // 13
    String user_agent)        // 14
```

## Emission points

| Action | Component | Resource | Result |
| --- | --- | --- | --- |
| `ORDER_SAVED` | `OrderService` | `order:{orderId}` | `SUCCESS` |
| `CHECKOUT_SUBMITTED` | `ShoppingCartService` | `cart:{cartId}` | `SUCCESS` |
| `CHECKOUT_FAILED` | `ShoppingCartService` | `cart:{cartId}` | `FAILURE` |

`details` carries `orderId`, `orderValue`, `itemCount`, `customerEmail` / `cartId`, `cartTotal`, `error`.

## Runtime behaviour

- Emission is fire-and-forget (`logEventAsync`); a failure is logged and **never** propagated, so a checkout
  succeeds even when the collector is unreachable.
- Event construction lives in `com.redhat.coolstore.audit.AuditEvents` (unit tested component by component).
- `audit.enabled=false` disables emission (used by the test profile); the default is enabled.
- The container filesystem is no longer written: the `./device-inventory-audit-logs` directory is gone. The
  library's file-system logger remains available only through an explicit fallback configuration
  (`AuditConfiguration.setLogDirectory`) and is not used by the default profile.
- `AuditService` closes the logger in `@PreDestroy` (graceful shutdown).

## Configuration

| Env var | Property | Default |
| --- | --- | --- |
| `AUDIT_ENABLED` | `audit.enabled` | `true` |
| `AUDIT_STREAM_HOST` | `audit.stream.host` | `localhost` |
| `AUDIT_STREAM_PORT` | `audit.stream.port` | `6514` |
| `AUDIT_STREAM_PROTOCOL` | `audit.stream.protocol` | `TCP` |
| `AUDIT_STREAM_BUFFER_SIZE` | `audit.stream.buffer.size` | `8192` |
| `AUDIT_STREAM_RECONNECT_MS` | `audit.stream.reconnect.ms` | `5000` |

## Konveyor rule closure

| Rule | Resolution |
| --- | --- |
| `audit-logging-0001` (dependency < 1.9) | dependency is `2.0.0` from the vendored Maven repository |
| `audit-logging-0002` (`AuditEvent.builder()`) | record constructor only |
| `audit-logging-0003` (`FileSystemAuditLogger` import) | replaced by `StreamableAuditLogger` |
| `audit-logging-0004` (`logEvent`) | only `logEventAsync` is called |
| `audit-logging-0005` (`logSuccess`/`logFailure`) | full `AuditEvent` + `logEventAsync` |
