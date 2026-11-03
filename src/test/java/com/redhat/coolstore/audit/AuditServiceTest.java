package com.redhat.coolstore.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;

import io.quarkus.test.junit.QuarkusTest;

/** Verifies the 2.0.0 API surface is used correctly and fails safe. */
@QuarkusTest
class AuditServiceTest {

    @Inject
    AuditService auditService;

    @Test
    @DisplayName("An order can be audited without a reachable collector")
    void auditIsFailureTolerant() {
        Order order = new Order();
        order.setOrderId(42L);
        order.setOrderValue(10.49);

        assertDoesNotThrow(() -> auditService.orderSaved(order));
        assertDoesNotThrow(() -> auditService.orderSaveFailed(order, new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("AuditEvent 2.0.0 record carries all 14 components")
    void auditEventRecord() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", 42L);
        AuditEvent event = new AuditEvent(
                Instant.now(), "ORDER", "user1", "session1", "coolstore-monolith", "order-service",
                "SAVE_ORDER", "orders/42", AuditResult.SUCCESS, "Order persisted",
                details, "corr-1", "127.0.0.1", "junit");

        assertNotNull(event.timestamp());
        assertEquals("ORDER", event.event_type());
        assertEquals(AuditResult.SUCCESS, event.result());
        assertEquals("orders/42", event.resource());
        assertEquals(42L, event.details().get("orderId"));
    }

    @Test
    @DisplayName("Order audit events accept a multi-line order without throwing")
    void orderWithItems() {
        Order order = new Order();
        order.setOrderId(43L);
        OrderItem item = new OrderItem();
        item.setProductId("329299");
        item.setQuantity(1);
        order.setItemList(List.of(item));

        assertDoesNotThrow(() -> auditService.orderSaved(order));
    }
}
