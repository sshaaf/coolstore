# 0002 — Start emitting audit events on order creation

* Status: accepted
* Context: the EAP code constructed `FileSystemAuditLogger` in `@PostConstruct` and closed it in
  `@PreDestroy`, but never called `logEvent`. The audit trail was empty; there is no historical
  behaviour to preserve.
* Decision: emit one `ORDER_CREATED` event per persisted order from `OrderService.save`, using
  `logEventAsync` with the 14-component `AuditEvent` record and `AuditResult.SUCCESS`. The future's
  `exceptionally` handler logs and swallows failures, so the collector can never break checkout.
* Consequences: new outbound TCP traffic to the audit collector (NetworkPolicy egress 9000);
  `AUDIT_ENABLED=false` provides a documented no-op mode where no collector exists.
