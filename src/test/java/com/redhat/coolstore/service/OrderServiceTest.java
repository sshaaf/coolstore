package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for OrderService.
 * Tests order persistence and retrieval operations.
 */
@QuarkusTest
public class OrderServiceTest {

    @Inject
    OrderService orderService;

    @Test
    public void testGetOrders() {
        List<Order> orders = orderService.getOrders();

        assertThat(orders).isNotNull();
    }

    @Test
    @Transactional
    public void testSaveOrder() {
        Order order = new Order();
        order.setCustomerName("Test Customer");
        order.setCustomerEmail("test@example.com");
        order.setOrderValue(100.0);
        order.setRetailPrice(100.0);
        order.setDiscount(0.0);
        order.setShippingFee(10.0);
        order.setShippingDiscount(0.0);

        orderService.save(order);

        assertThat(order.getOrderId()).isGreaterThan(0);
    }

    @Test
    @Transactional
    public void testSaveAndRetrieveOrder() {
        Order order = new Order();
        order.setCustomerName("John Doe");
        order.setCustomerEmail("john.doe@example.com");
        order.setOrderValue(50.0);
        order.setRetailPrice(60.0);
        order.setDiscount(10.0);
        order.setShippingFee(5.0);
        order.setShippingDiscount(0.0);

        orderService.save(order);
        long orderId = order.getOrderId();

        Order retrieved = orderService.getOrderById(orderId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getOrderId()).isEqualTo(orderId);
        assertThat(retrieved.getCustomerName()).isEqualTo("John Doe");
        assertThat(retrieved.getCustomerEmail()).isEqualTo("john.doe@example.com");
        assertThat(retrieved.getOrderValue()).isEqualTo(50.0);
    }

    @Test
    @Transactional
    public void testSaveOrderWithItems() {
        Order order = new Order();
        order.setCustomerName("Jane Smith");
        order.setCustomerEmail("jane@example.com");
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
        assertThat(retrieved.getItemList().get(0).getProductId()).isEqualTo("329299");
        assertThat(retrieved.getItemList().get(1).getProductId()).isEqualTo("329199");
    }

    @Test
    public void testGetOrderByIdWhenNotExists() {
        Order order = orderService.getOrderById(999999L);

        assertThat(order).isNull();
    }

    @Test
    @Transactional
    public void testOrderPersistence() {
        int initialSize = orderService.getOrders().size();

        Order order = new Order();
        order.setCustomerName("Test Persistence");
        order.setCustomerEmail("persist@test.com");
        order.setOrderValue(100.0);

        orderService.save(order);

        int newSize = orderService.getOrders().size();
        assertThat(newSize).isEqualTo(initialSize + 1);
    }

    @Test
    @Transactional
    public void testOrderWithAllFields() {
        Order order = new Order();
        order.setCustomerName("Complete Order");
        order.setCustomerEmail("complete@example.com");
        order.setOrderValue(100.0);
        order.setRetailPrice(120.0);
        order.setDiscount(20.0);
        order.setShippingFee(10.0);
        order.setShippingDiscount(2.0);

        orderService.save(order);

        Order retrieved = orderService.getOrderById(order.getOrderId());

        assertThat(retrieved.getCustomerName()).isEqualTo("Complete Order");
        assertThat(retrieved.getCustomerEmail()).isEqualTo("complete@example.com");
        assertThat(retrieved.getOrderValue()).isEqualTo(100.0);
        assertThat(retrieved.getRetailPrice()).isEqualTo(120.0);
        assertThat(retrieved.getDiscount()).isEqualTo(20.0);
        assertThat(retrieved.getShippingFee()).isEqualTo(10.0);
        assertThat(retrieved.getShippingDiscount()).isEqualTo(2.0);
    }

    @Test
    @Transactional
    public void testOrderItemsCascade() {
        Order order = new Order();
        order.setCustomerName("Cascade Test");
        order.setCustomerEmail("cascade@test.com");
        order.setOrderValue(50.0);

        OrderItem item = new OrderItem();
        item.setProductId("TEST-ITEM");
        item.setQuantity(5);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItemList(items);

        orderService.save(order);

        Order retrieved = orderService.getOrderById(order.getOrderId());

        assertThat(retrieved.getItemList()).hasSize(1);
        assertThat(retrieved.getItemList().get(0).getProductId()).isEqualTo("TEST-ITEM");
        assertThat(retrieved.getItemList().get(0).getQuantity()).isEqualTo(5);
    }
}
