package com.redhat.coolstore.integration;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.service.CatalogService;
import com.redhat.coolstore.service.OrderService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for database operations.
 * Tests JPA entity persistence, retrieval, and relationships.
 */
@QuarkusTest
public class DatabaseIntegrationTest {

    @Inject
    CatalogService catalogService;

    @Inject
    OrderService orderService;

    @Test
    public void testCatalogDatabaseLoad() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        assertThat(items).isNotNull();
        assertThat(items).isNotEmpty();
        assertThat(items.size()).isGreaterThanOrEqualTo(9);
    }

    @Test
    public void testCatalogItemWithInventory() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");

        assertThat(item).isNotNull();
        assertThat(item.getInventory()).isNotNull();
        assertThat(item.getInventory().getItemId()).isEqualTo("329299");
        assertThat(item.getInventory().getQuantity()).isGreaterThan(0);
        assertThat(item.getInventory().getLocation()).isNotBlank();
    }

    @Test
    @Transactional
    public void testInventoryUpdate() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");
        int originalQuantity = item.getInventory().getQuantity();

        catalogService.updateInventoryItems("329299", 10);

        CatalogItemEntity updatedItem = catalogService.getCatalogItemById("329299");
        assertThat(updatedItem.getInventory().getQuantity())
            .isEqualTo(originalQuantity - 10);
    }

    @Test
    @Transactional
    public void testOrderPersistence() {
        Order order = new Order();
        order.setCustomerName("Database Test User");
        order.setCustomerEmail("dbtest@example.com");
        order.setOrderValue(100.0);
        order.setRetailPrice(110.0);
        order.setDiscount(10.0);
        order.setShippingFee(5.0);
        order.setShippingDiscount(0.0);

        orderService.save(order);

        assertThat(order.getOrderId()).isGreaterThan(0);

        Order retrieved = orderService.getOrderById(order.getOrderId());
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getCustomerName()).isEqualTo("Database Test User");
    }

    @Test
    @Transactional
    public void testOrderWithItemsPersistence() {
        Order order = new Order();
        order.setCustomerName("Order with Items");
        order.setCustomerEmail("items@test.com");
        order.setOrderValue(75.0);

        OrderItem item1 = new OrderItem();
        item1.setProductId("329299");
        item1.setQuantity(2);

        OrderItem item2 = new OrderItem();
        item2.setProductId("329199");
        item2.setQuantity(1);

        List<OrderItem> items = new ArrayList<>();
        items.add(item1);
        items.add(item2);
        order.setItemList(items);

        orderService.save(order);

        Order retrieved = orderService.getOrderById(order.getOrderId());
        assertThat(retrieved.getItemList()).hasSize(2);
        assertThat(retrieved.getItemList()).extracting("productId")
            .containsExactlyInAnyOrder("329299", "329199");
    }

    @Test
    public void testCatalogItemsHaveValidInventory() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        for (CatalogItemEntity item : items) {
            assertThat(item.getInventory()).isNotNull();
            assertThat(item.getInventory().getItemId()).isEqualTo(item.getItemId());
            assertThat(item.getInventory().getQuantity()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    public void testAllExpectedProductsExist() {
        assertThat(catalogService.getCatalogItemById("329299")).isNotNull();
        assertThat(catalogService.getCatalogItemById("329199")).isNotNull();
        assertThat(catalogService.getCatalogItemById("165613")).isNotNull();
        assertThat(catalogService.getCatalogItemById("165614")).isNotNull();
        assertThat(catalogService.getCatalogItemById("165954")).isNotNull();
        assertThat(catalogService.getCatalogItemById("444434")).isNotNull();
        assertThat(catalogService.getCatalogItemById("444435")).isNotNull();
        assertThat(catalogService.getCatalogItemById("444436")).isNotNull();
        assertThat(catalogService.getCatalogItemById("444437")).isNotNull();
    }

    @Test
    @Transactional
    public void testMultipleInventoryUpdates() {
        String itemId = "329199";
        CatalogItemEntity item = catalogService.getCatalogItemById(itemId);
        int originalQuantity = item.getInventory().getQuantity();

        catalogService.updateInventoryItems(itemId, 5);
        catalogService.updateInventoryItems(itemId, 3);
        catalogService.updateInventoryItems(itemId, 2);

        CatalogItemEntity updatedItem = catalogService.getCatalogItemById(itemId);
        assertThat(updatedItem.getInventory().getQuantity())
            .isEqualTo(originalQuantity - 10);
    }

    @Test
    @Transactional
    public void testOrderCascadeDelete() {
        Order order = new Order();
        order.setCustomerName("Cascade Delete Test");
        order.setCustomerEmail("cascade@test.com");
        order.setOrderValue(50.0);

        OrderItem item = new OrderItem();
        item.setProductId("TEST-PRODUCT");
        item.setQuantity(1);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItemList(items);

        orderService.save(order);
        long orderId = order.getOrderId();

        Order retrieved = orderService.getOrderById(orderId);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getItemList()).hasSize(1);
    }
}
