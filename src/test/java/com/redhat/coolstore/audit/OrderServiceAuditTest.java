package com.redhat.coolstore.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.service.OrderService;

/**
 * Verifies that persisting an order emits one audit event with the expected record components
 * (rules audit-logging-0003/0004/0005 closed: StreamableAuditLogger + logEventAsync + record).
 */
class OrderServiceAuditTest {

    static class CapturingAuditService extends AuditService {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void emit(AuditEvent event) {
            events.add(event);
        }
    }

    private static Order order() {
        Order order = new Order();
        order.setOrderId(42L);
        order.setCustomerName("Sven Karlsson");
        order.setCustomerEmail("sven@gmail.com");
        order.setOrderValue(26.99);
        order.setRetailPrice(24.0);
        order.setDiscount(-5.0);
        order.setShippingFee(2.99);
        order.setShippingDiscount(0.0);
        OrderItem item = new OrderItem();
        item.setProductId("329299");
        item.setQuantity(2);
        order.setItemList(new ArrayList<>(List.of(item)));
        return order;
    }

    @Test
    @DisplayName("save() persists the order and emits one ORDER_SAVED audit event")
    void saveEmitsAuditEvent() {
        EntityManager em = mock(EntityManager.class);
        CapturingAuditService audit = new CapturingAuditService();
        OrderService orderService = new OrderService(em, audit);
        Order order = order();

        orderService.save(order);

        verify(em).persist(order);
        assertEquals(1, audit.events.size());

        AuditEvent event = audit.events.get(0);
        assertNotNull(event.timestamp());
        assertEquals("ORDER", event.event_type());
        assertEquals("anonymous", event.user_id());
        assertEquals("coolstore", event.application());
        assertEquals("OrderService", event.component());
        assertEquals("ORDER_SAVED", event.action());
        assertEquals("order:42", event.resource());
        assertEquals(AuditResult.SUCCESS, event.result());
        assertEquals("Order 42 persisted", event.message());
        assertEquals(2, event.details().get("itemCount"));
        assertEquals(42L, event.details().get("orderId"));
        assertEquals("order-42", event.correlation_id());
    }

    @Test
    @DisplayName("AuditEvent is a 14-component record as required by the 2.0.0 API")
    void auditEventIsARecordWithFourteenComponents() {
        AuditEvent event = AuditEvents.orderSaved(order());
        assertEquals(14, AuditEvent.class.getRecordComponents().length);
        assertEquals(List.of("timestamp", "event_type", "user_id", "session_id", "application", "component",
                        "action", "resource", "result", "message", "details", "correlation_id", "source_ip",
                        "user_agent"),
                List.of(AuditEvent.class.getRecordComponents()).stream()
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(AuditResult.SUCCESS, event.result());
    }

    @Test
    void failureEventCarriesFailureResult() {
        AuditEvent event = AuditEvents.checkoutFailed("cart-1", new IllegalStateException("boom"));
        assertEquals("CHECKOUT_FAILED", event.action());
        assertEquals(AuditResult.FAILURE, event.result());
        assertEquals("cart:cart-1", event.resource());
        assertEquals("IllegalStateException", event.details().get("error"));
    }

    @Test
    void disabledAuditServiceDropsEventsSilently() {
        AuditService service = new AuditService(); // no @PostConstruct -> disabled
        service.emit(AuditEvents.orderSaved(order()));
        assertEquals(false, service.isReady());
    }
}
