package com.redhat.coolstore.service;

import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.audit.AuditPublisher;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private EntityManager em;
    private CatalogService catalogService;
    private AuditPublisher auditPublisher;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        catalogService = mock(CatalogService.class);
        auditPublisher = mock(AuditPublisher.class);
        orderService = new OrderService(em, catalogService, auditPublisher);
        orderService.auditApplication = "coolstore";
        orderService.auditComponent = "order-service";
    }

    private static Order order() {
        OrderItem item = new OrderItem();
        item.setProductId("329299");
        item.setQuantity(2);
        Order order = new Order();
        order.setOrderValue(17.99);
        order.setItemList(List.of(item));
        return order;
    }

    @Test
    void savePersistsAndAuditsSuccess() {
        Order order = order();

        orderService.save(order);

        verify(em).persist(order);
        verify(auditPublisher).publish(any());
    }

    @Test
    void saveAuditsFailureAndRethrows() {
        Order order = order();
        doThrow(new IllegalStateException("db down")).when(em).persist(order);

        assertThatThrownBy(() -> orderService.save(order)).isInstanceOf(IllegalStateException.class);
        verify(auditPublisher).publish(any());
    }

    @Test
    void processOrderSavesThenDeductsInventoryPerItem() {
        Order order = order();

        orderService.processOrder(order);

        verify(em).persist(order);
        verify(catalogService).updateInventoryItems("329299", 2);
    }

    @Test
    void inventoryFailureDoesNotLeaveASavedOrder() {
        Order order = order();
        when(catalogService.getCatalogItemById("329299")).thenReturn(null);
        doThrow(new IllegalArgumentException("Unknown catalog item: 329299"))
                .when(catalogService).updateInventoryItems(eq("329299"), eq(2));

        assertThatThrownBy(() -> orderService.processOrder(order))
                .isInstanceOf(IllegalArgumentException.class);
        verify(catalogService).updateInventoryItems("329299", 2);
    }

    @Test
    void getOrderByIdDelegatesToEntityManager() {
        Order order = order();
        when(em.find(Order.class, 7L)).thenReturn(order);

        orderService.getOrderById(7L);

        verify(em).find(Order.class, 7L);
    }

    @Test
    void auditMessageCarriesTheOrderResource() {
        orderService.save(order());

        ArgumentCaptor<com.enterprise.audit.logging.model.AuditEvent> captor =
                ArgumentCaptor.forClass(com.enterprise.audit.logging.model.AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().result()).isEqualTo(AuditResult.SUCCESS);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().resource()).startsWith("orders/");
    }

    @Test
    void noInventoryUpdateWhenOrderHasNoItems() {
        Order empty = new Order();
        empty.setItemList(List.of());

        orderService.processOrder(empty);

        verify(em).persist(empty);
        verify(catalogService, never()).updateInventoryItems(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
