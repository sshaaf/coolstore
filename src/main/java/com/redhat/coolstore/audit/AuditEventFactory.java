package com.redhat.coolstore.audit;

import com.enterprise.audit.logging.model.AuditContext;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps CoolStore order events onto the v2 14-component {@link AuditEvent} record.
 * v2.0.0 has no {@code builder()}: the canonical constructor is used directly.
 */
public final class AuditEventFactory {

    public static final String EVENT_ORDER_PROCESSING = "ORDER_PROCESSING";
    public static final String ACTION_ORDER_SAVE = "SAVE";
    public static final String ACTION_CHECKOUT = "CHECKOUT";

    private AuditEventFactory() {
    }

    public static AuditEvent orderEvent(String application,
                                        String component,
                                        String action,
                                        String resource,
                                        AuditResult result,
                                        String message,
                                        Map<String, Object> details) {
        return new AuditEvent(
                Instant.now(),                                        // timestamp
                EVENT_ORDER_PROCESSING,                               // event_type
                orDefault(AuditContext.getUserId(), "system"),        // user_id
                orDefault(AuditContext.getSessionId(), ""),           // session_id
                application,                                          // application
                component,                                            // component
                action,                                               // action
                resource,                                             // resource
                result == null ? AuditResult.UNKNOWN : result,        // result
                message,                                              // message
                details == null ? Map.of() : details,                 // details
                orDefault(AuditContext.getCorrelationId(), ""),       // correlation_id
                orDefault(AuditContext.getSourceIp(), ""),            // source_ip
                orDefault(AuditContext.getUserAgent(), ""));          // user_agent
    }

    public static AuditEvent orderSaved(String application,
                                        String component,
                                        Order order,
                                        AuditResult result,
                                        String message) {
        return orderEvent(application, component, ACTION_ORDER_SAVE,
                "orders/" + (order == null ? "unknown" : order.getOrderId()),
                result, message, details(order));
    }

    public static Map<String, Object> details(Order order) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (order == null) {
            return details;
        }
        details.put("order_id", order.getOrderId());
        details.put("order_value", order.getOrderValue());
        details.put("retail_price", order.getRetailPrice());
        details.put("discount", order.getDiscount());
        details.put("shipping_fee", order.getShippingFee());
        details.put("shipping_discount", order.getShippingDiscount());
        details.put("customer_email", order.getCustomerEmail());
        details.put("item_count", order.getItemList() == null ? 0 : order.getItemList().size());
        if (order.getItemList() != null) {
            Map<String, Integer> quantities = new LinkedHashMap<>();
            for (OrderItem item : order.getItemList()) {
                quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
            details.put("item_quantities", quantities);
        }
        return details;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
