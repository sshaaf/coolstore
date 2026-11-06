package com.redhat.coolstore.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.service.CatalogService;
import com.redhat.coolstore.service.OrderService;

/**
 * Hibernate ORM 6 mapping round-trips against the Flyway-managed PostgreSQL schema.
 */
@QuarkusTest
class OrderPersistenceIT {

    @Inject
    OrderService orderService;

    @Inject
    CatalogService catalogService;

    @Test
    @DisplayName("CatalogItemEntity + InventoryEntity round-trip through the 1:1 primary-key join")
    void catalogItemAndInventoryRoundTrip() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");
        assertNotNull(item);
        assertEquals("Quarkus T-shirt", item.getName());
        assertNotNull(item.getInventory());
        assertEquals("Raleigh", item.getInventory().getLocation());
        assertEquals(736, item.getInventory().getQuantity());
    }

    @Test
    @Transactional
    @DisplayName("An Order with two items is persisted with generated ids and the ORDER_ID foreign key")
    void orderWithItemsRoundTrip() {
        Order order = new Order();
        order.setCustomerName("Round Trip");
        order.setCustomerEmail("round@trip.io");
        order.setOrderValue(99.5);
        order.setRetailPrice(90.0);
        order.setDiscount(-10.5);
        order.setShippingFee(9.5);
        order.setShippingDiscount(0.0);
        order.setItemList(new ArrayList<>(List.of(item("329299", 2), item("329199", 1))));

        orderService.save(order);

        assertNotNull(order.getOrderId());

        Order loaded = orderService.getOrderById(order.getOrderId());
        assertNotNull(loaded);
        assertEquals("Round Trip", loaded.getCustomerName());
        assertEquals(2, loaded.getItemList().size());
        assertEquals("329299", loaded.getItemList().get(0).getProductId());
        assertEquals(2, loaded.getItemList().get(0).getQuantity());
    }

    private static OrderItem item(String productId, int quantity) {
        OrderItem orderItem = new OrderItem();
        orderItem.setProductId(productId);
        orderItem.setQuantity(quantity);
        return orderItem;
    }
}
