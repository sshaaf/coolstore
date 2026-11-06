package com.redhat.coolstore.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.service.CatalogService;
import com.redhat.coolstore.service.OrderService;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;

/**
 * Persistence contract (Story 3 tasks 3.5/3.6). The schema is created exclusively by the two
 * byte-identical Flyway migrations; Hibernate only maps onto it
 * ({@code quarkus.hibernate-orm.database.generation=none}).
 */
@QuarkusTest
class PersistenceContractTest {

    @Inject
    Flyway flyway;

    @Inject
    CatalogService catalogService;

    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    @Test
    @DisplayName("empty database: both migrations applied, 9 catalogue and 9 inventory rows")
    void migrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(2, applied.length, "V1_1 and V1_2 must be applied");
        assertEquals("1.1", applied[0].getVersion().getVersion());
        assertEquals("1.2", applied[1].getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        assertNotNull(applied[0].getChecksum());

        assertEquals(9, count("PRODUCT_CATALOG"));
        assertEquals(9, count("INVENTORY"));
    }

    @Test
    @DisplayName("already-migrated database: a second migrate() is a no-op with no checksum error")
    void migrationsAreIdempotent() {
        int executed = flyway.migrate().migrationsExecuted;
        assertEquals(0, executed, "no migration may re-run on an already-migrated database");
        assertEquals(1, flyway.validateWithResult().validationSuccessful ? 1 : 0);
    }

    @Test
    @DisplayName("CatalogItemEntity round-trips location, link and quantity from INVENTORY")
    void catalogItemRoundTrip() {
        CatalogItemEntity item = catalogService.getCatalogItemById("444436");
        assertNotNull(item);
        assertEquals("Women RHEL 8 t-shirt", item.getName());
        InventoryEntity inventory = item.getInventory();
        assertNotNull(inventory);
        assertEquals("Tokyo", inventory.getLocation());
        assertEquals("http://maps.google.com/?q=Tokyo", inventory.getLink());
        assertEquals(230, inventory.getQuantity());
    }

    @Test
    @Transactional
    @DisplayName("Order + OrderItem persist through the untouched FK2BFF474F65938E8 constraint")
    void orderRoundTrip() {
        Order order = new Order();
        order.setCustomerName("Sven Karlsson");
        order.setCustomerEmail("sven@gmail.com");
        order.setOrderValue(40.0);
        order.setRetailPrice(35.5);
        order.setDiscount(-7.5);
        order.setShippingFee(4.99);
        order.setShippingDiscount(0.0);

        OrderItem first = new OrderItem();
        first.setProductId("329299");
        first.setQuantity(3);
        OrderItem second = new OrderItem();
        second.setProductId("444435");
        second.setQuantity(1);
        order.setItemList(List.of(first, second));

        orderService.save(order);

        assertTrue(order.getOrderId() > 0, "orderId comes from the hibernate_sequence");

        em.flush();
        em.clear();

        Order reloaded = orderService.getOrderById(order.getOrderId());
        assertNotNull(reloaded);
        assertEquals("Sven Karlsson", reloaded.getCustomerName());
        assertEquals(2, reloaded.getItemList().size());
        assertEquals(3, reloaded.getItemList().stream()
                .filter(i -> "329299".equals(i.getProductId())).findFirst().orElseThrow().getQuantity());
        assertEquals(2, count("ORDER_ITEMS where ORDER_ID = " + order.getOrderId()));
    }

    @Test
    @DisplayName("updateInventoryItems decrements INVENTORY.quantity by exactly the requested amount")
    void inventoryDecrement() {
        int before = catalogService.getCatalogItemById("165614").getInventory().getQuantity();

        catalogService.updateInventoryItems("165614", 4);

        int after = catalogService.getCatalogItemById("165614").getInventory().getQuantity();
        assertEquals(before - 4, after);
    }

    private int count(String from) {
        Number result = (Number) em.createNativeQuery("select count(*) from " + from).getSingleResult();
        return result.intValue();
    }
}
