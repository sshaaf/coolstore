package com.redhat.coolstore.audit;

import com.enterprise.audit.logging.model.AuditContext;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventFactoryTest {

    @AfterEach
    void clearContext() {
        AuditContext.clear();
    }

    private static Order order() {
        OrderItem item = new OrderItem();
        item.setProductId("329299");
        item.setQuantity(2);
        Order order = new Order();
        order.setOrderId(42L);
        order.setOrderValue(17.99);
        order.setRetailPrice(15.00);
        order.setDiscount(-5.00);
        order.setShippingFee(2.99);
        order.setShippingDiscount(0);
        order.setCustomerEmail("sven@gmail.com");
        order.setItemList(List.of(item));
        return order;
    }

    @Test
    void everyRecordComponentIsPopulated() {
        AuditContext.setUserId("alice");
        AuditContext.setSessionId("session-1");
        AuditContext.setCorrelationId("corr-1");
        AuditContext.setSourceIp("10.0.0.1");
        AuditContext.setUserAgent("junit");

        AuditEvent event = AuditEventFactory.orderSaved("coolstore", "order-service", order(),
                AuditResult.SUCCESS, "Order persisted");

        assertThat(event.timestamp()).isNotNull();
        assertThat(event.event_type()).isEqualTo("ORDER_PROCESSING");
        assertThat(event.user_id()).isEqualTo("alice");
        assertThat(event.session_id()).isEqualTo("session-1");
        assertThat(event.application()).isEqualTo("coolstore");
        assertThat(event.component()).isEqualTo("order-service");
        assertThat(event.action()).isEqualTo("SAVE");
        assertThat(event.resource()).isEqualTo("orders/42");
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(event.message()).isEqualTo("Order persisted");
        assertThat(event.details()).containsEntry("order_id", 42L)
                .containsEntry("item_count", 1)
                .containsEntry("order_value", 17.99);
        assertThat(event.correlation_id()).isEqualTo("corr-1");
        assertThat(event.source_ip()).isEqualTo("10.0.0.1");
        assertThat(event.user_agent()).isEqualTo("junit");
    }

    @Test
    void missingContextUsesSafeDefaults() {
        AuditEvent event = AuditEventFactory.orderSaved("coolstore", "order-service", order(),
                AuditResult.FAILURE, "boom");

        assertThat(event.user_id()).isEqualTo("system");
        assertThat(event.session_id()).isEmpty();
        assertThat(event.correlation_id()).isEmpty();
        assertThat(event.source_ip()).isEmpty();
        assertThat(event.user_agent()).isEmpty();
        assertThat(event.result()).isEqualTo(AuditResult.FAILURE);
    }

    @Test
    void unknownResultFallsBackToUnknown() {
        AuditEvent event = AuditEventFactory.orderEvent("coolstore", "order-service", "CHECKOUT",
                "carts/c1", null, "checkout", null);

        assertThat(event.result()).isEqualTo(AuditResult.UNKNOWN);
        assertThat(event.details()).isEmpty();
    }
}
