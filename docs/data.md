# Data layer (Group 3)

## Datasource

Configured exclusively in `src/main/resources/application.properties`; `persistence.xml` and the
`CoolstoreDS` JNDI name are gone.

| Setting | Value |
|---|---|
| kind | `postgresql` |
| url | `${DB_URL}` (prod, no default), `jdbc:postgresql://localhost:5432/postgresDB` (dev) |
| user / password | `${DB_USER}` / `${DB_PASSWORD}` (Secret) |
| pool | `quarkus.datasource.jdbc.min-size=2`, `max-size=${DB_POOL_MAX_SIZE:20}` |
| schema | `quarkus.hibernate-orm.database.generation=none`, `log.sql=false` (old `show_sql=false`) |
| tests | H2 in PostgreSQL mode (`%test`), same Flyway migrations |

The old persistence-unit properties are preserved in effect: no SQL logging, no schema generation,
sequence-based generated keys for `Order.orderId` and `OrderItem.id`
(`@SequenceGenerator(name="hibernate_sequence", allocationSize=1)` — the sequence created by V1_1).

## Entity mapping

Hibernate 6 defaults to `CamelCaseToUnderscoresNamingStrategy`, which would have renamed
`itemId` → `item_id` and broken against the unquoted mixed-case DDL. Every column now carries an
explicit `@Column(name = …)`:

| Entity | Table | Columns |
|---|---|---|
| `CatalogItemEntity` | `PRODUCT_CATALOG` | `itemId` (PK), `name`, `description`, `price`; `@PrimaryKeyJoinColumn(itemId)` → `InventoryEntity` |
| `InventoryEntity` | `INVENTORY` | `itemId` (PK), `location`, `quantity`, `link` |
| `Order` | `ORDERS` | `orderId` (PK, sequence), `customerName`, `customerEmail`, `orderValue`, `retailPrice`, `discount`, `shippingFee`, `shippingDiscount` |
| `OrderItem` | `ORDER_ITEMS` | `ID` (PK, sequence), `productId`, `quantity`; `@JoinColumn(name="ORDER_ID")` keeps FK `FK2BFF474F65938E8` |

`InventoryEntity` lost its `@XmlRootElement` (JAXB); Quarkus REST serialises with Jackson and the
JSON field names are unchanged.

`Resources` (the `@Dependent` `EntityManager` producer) was deleted — Quarkus injects
`EntityManager` directly; `@Inject EntityManager` call sites in `CatalogService`, `ProductService`
and `OrderService` are unchanged.

## Flyway

`quarkus-flyway` replaces the EJB `@Singleton @Startup` bean (`new Flyway()`/`baseline()`/`migrate()`
is removed in Flyway 9/10).

```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration
quarkus.flyway.baseline-on-migrate=false   # true only for databases predating flyway_schema_history
quarkus.flyway.validate-on-migrate=true
```

`V1_1__CreateSchema.sql` and `V1_2__AddInitialData.sql` are **byte-identical** (checksums preserved),
which is what keeps the JBoss EAP rollback path viable: no new migration was added, so the old WAR can
re-deploy against the same database. `MigrationGuardrailsTest` fails the build if a third migration
file appears or the DDL markers disappear.
