# Vendor lock-in removed

| Vendor API | Where it was | What replaced it |
| --- | --- | --- |
| `weblogic.application.ApplicationLifecycleListener` / `ApplicationLifecycleEvent` | `src/main/java/weblogic/application` | **nothing** - CDI `@Observes StartupEvent`/`ShutdownEvent` (`utils/StartupLifecycle`) |
| `weblogic.i18n.logging.NonCatalogLogger` | `src/main/java/weblogic/i18n/logging` | **nothing** - `org.jboss.logging.Logger` |
| `weblogic.jndi.WLInitialContextFactory`, `t3://localhost:7001`, `PortableRemoteObject.narrow` | `InventoryNotificationMDB` | **nothing** - the class was dead code; its low-stock rule lives on as a reactive-messaging consumer |
| `org.wildfly.naming.client.WildFlyInitialContextFactory` + `ejb:/ROOT/ShippingService!...` | `ShoppingCartService.lookupShippingServiceRemote()` | direct CDI injection of `ShippingService` |
| `java:jboss/datasources/CoolstoreDS` | `persistence.xml`, `DataBaseMigrationStartup` | `quarkus.datasource.*` (env driven) |
| `java:/topic/orders` | `ShoppingCartOrderProcessor`, `OrderServiceMDB` | `@Channel("orders")` / `@Incoming` (AMQP 1.0) |
| `javax.*` (Java EE 7) | 30 sources | `jakarta.*` (Jakarta EE 10) |
| `org.jboss.spec.javax.jms` / `org.jboss.spec.javax.rmi` spec JARs | `pom.xml` | removed with the APIs |
| `system`-scoped `com.enterprise:audit-logging-library:1.0.0` + `<systemPath>` | `pom.xml`, `lib/` | normal dependency `2.0.0` resolved from the vendored repository `lib/maven-repo` |
| WAR packaging / `ROOT.war` | `pom.xml` | Quarkus fast-jar (`target/quarkus-app/quarkus-run.jar`) |
| `web.xml`, `beans.xml`, `META-INF/persistence.xml` | descriptors | Quarkus configuration (`application.properties`) |
| `health.jsp` (a file containing `1`) | `src/main/webapp` | SmallRye Health `/q/health/live|ready` |
| `@ApplicationPath("/services")` | `RestApplication` | `quarkus.rest.path=/services` |

## Why nothing replaces the WebLogic shims

They existed only to make the application *compile and deploy* on WebLogic. The functions they provided
were: application lifecycle logging (`StartupListener`) and a logging facade (`NonCatalogLogger`). CDI events
and JBoss Logging cover both, without a vendor API.

## Deliberately unchanged (technical debt, out of scope)

- AngularJS 1.x, PatternFly and jQuery vendored under `META-INF/resources/bower_components` (EOL, ~48 MB,
  2,482 files). Served untouched.
- `realm-export.json` retains the legacy `eap` realm definition for Keycloak import.
- Flyway migrations `V1_1`/`V1_2` are byte-identical: the schema and seed data are the source of truth.
