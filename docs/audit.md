# Audit logging (Group 4)

## 1.0.0 → 2.0.0 API mapping

| 1.0.0 (JBoss EAP) | 2.0.0 (Quarkus) |
|---|---|
| `system`-scoped `lib/audit-logging-library-1.0.0.jar`, `${project.basedir}` systemPath | normal `compile` dependency `com.enterprise:audit-logging-library:2.0.0`, served from the project-local repository `lib/repo` |
| `FileSystemAuditLogger` writing `./device-inventory-audit-logs` | `StreamableAuditLogger` — one NDJSON line per event over TCP (virtual threads, async) |
| `AuditConfiguration#setLogDirectory/setAutoCreateDirectory` | `setStreamHost/…Port/…Protocol/…BufferSize/…ReconnectMs` from `audit.stream.*` |
| `AuditEvent.builder()…build()` (POJO) | `new AuditEvent(...)` — 14-component **record** |
| `logSuccess` / `logFailure` / `logDenied` | `AuditResult.SUCCESS/FAILURE/DENIED/…` on the record |
| `logEvent(...)` (now `@Deprecated`) | `logEventAsync(...)` (preferred) / `logEventAsyncSafe(...)` |

Record component order (positional constructor):
`timestamp, event_type, user_id, session_id, application, component, action, resource, result,
message, details, correlation_id, source_ip, user_agent`.
The serialized NDJSON uses the snake_case component names and serialises `timestamp` as epoch seconds
(pinned by `AuditLoggingTest`).

## Where it lives

* `com.redhat.coolstore.audit.AuditLoggerProducer` — `@Produces @Singleton AuditLogger`;
  builds `StreamableAuditLogger` from configuration, `@PreDestroy` closes it.
* `com.redhat.coolstore.audit.NoOpAuditLogger` — used when `audit.enabled=false` (dev/test) so no
  socket is ever opened during `@QuarkusTest`.
* `OrderService.save` — emits `ORDER_CREATED` (`ORDER_CREATED`, component `OrderService`, action
  `save`, resource `order:<id>`, `AuditResult.SUCCESS`, details `orderValue`/`itemCount`) via
  `logEventAsync(...).exceptionally(...)`. A missing collector, a closed logger
  (`RejectedExecutionException`) or a serialization failure is logged and swallowed: **checkout can
  never fail because of audit**.

## Configuration

| Env var | Property | Default |
|---|---|---|
| `AUDIT_ENABLED` | `audit.enabled` | `true` (prod), `false` (dev/test) |
| `AUDIT_STREAM_HOST` | `audit.stream.host` | `localhost` (dev only) |
| `AUDIT_STREAM_PORT` | `audit.stream.port` | `9000` |
| `AUDIT_STREAM_PROTOCOL` | `audit.stream.protocol` | `TCP` |
| `AUDIT_STREAM_BUFFER_SIZE` | `audit.stream.buffer-size` | `8192` |
| `AUDIT_STREAM_RECONNECT_MS` | `audit.stream.reconnect-ms` | `5000` |

## Operating without a collector

If no collector exists in an environment, set `AUDIT_ENABLED=false` (no-op logger) rather than leaving
a failing transport in place. When enabled with a dead collector, `logEventAsync` completes
exceptionally and the failure is logged at WARN — documented operational assumption.

## Rule IDs

`rgctl rules run rules` reports **0 violations** for `audit-logging-0001 … 0005` (`audit-logging-0003`
was the pre-migration `FileSystemAuditLogger` import at `OrderService.java:5`). Local collector:
`nc -lk 9000` and run `mvn quarkus:dev` with `AUDIT_ENABLED=true`.
