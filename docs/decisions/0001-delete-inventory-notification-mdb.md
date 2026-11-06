# 0001 — Delete `InventoryNotificationMDB`

* Status: accepted
* Context: `InventoryNotificationMDB` is not annotated with `@MessageDriven` (never activated on
  JBoss EAP), and its `init()` uses WebLogic-only JNDI (`t3://localhost:7001`,
  `weblogic.jndi.WLInitialContextFactory`) plus `PortableRemoteObject.narrow`. It cannot run on any
  target platform.
* Decision: delete it. The low-stock threshold (`LOW_THRESHOLD = 50`) and the alert message are
  preserved as documentation in `docs/services.md`; re-introducing it as a second `@Incoming` consumer
  with a distinct channel is a product decision, not a migration step.
* Consequence: no low-stock notification is emitted (it never was on EAP). `MigrationGuardrailsTest`
  prevents the class from coming back.
