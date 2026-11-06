package com.redhat.coolstore.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.ShoppingCart;

/**
 * Factory for the audit events this application emits.
 *
 * <p>{@link AuditEvent} is a record with 14 components, in this exact order:
 * {@code timestamp, event_type, user_id, session_id, application, component, action, resource, result,
 * message, details, correlation_id, source_ip, user_agent}.
 */
public final class AuditEvents {

    public static final String APPLICATION = "coolstore";
    public static final String EVENT_TYPE = "ORDER";
    public static final String ANONYMOUS = "anonymous";

    private AuditEvents() {
    }

    public static AuditEvent orderSaved(Order order) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.getOrderId());
        details.put("orderValue", order.getOrderValue());
        details.put("itemCount", order.getItemList() == null ? 0 : order.getItemList().size());
        details.put("customerEmail", order.getCustomerEmail());
        return new AuditEvent(
                Instant.now(),
                EVENT_TYPE,
                ANONYMOUS,
                null,
                APPLICATION,
                "OrderService",
                "ORDER_SAVED",
                "order:" + order.getOrderId(),
                AuditResult.SUCCESS,
                "Order " + order.getOrderId() + " persisted",
                details,
                "order-" + order.getOrderId(),
                null,
                null);
    }

    public static AuditEvent checkoutSubmitted(String cartId, ShoppingCart cart) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cartId", cartId);
        details.put("cartTotal", cart == null ? 0d : cart.getCartTotal());
        details.put("itemCount", cart == null ? 0 : cart.getShoppingCartItemList().size());
        return new AuditEvent(
                Instant.now(),
                EVENT_TYPE,
                ANONYMOUS,
                cartId,
                APPLICATION,
                "ShoppingCartService",
                "CHECKOUT_SUBMITTED",
                "cart:" + cartId,
                AuditResult.SUCCESS,
                "Checkout published for cart " + cartId,
                details,
                "checkout-" + cartId,
                null,
                null);
    }

    public static AuditEvent checkoutFailed(String cartId, Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cartId", cartId);
        details.put("error", cause == null ? "unknown" : cause.getClass().getSimpleName());
        return new AuditEvent(
                Instant.now(),
                EVENT_TYPE,
                ANONYMOUS,
                cartId,
                APPLICATION,
                "ShoppingCartService",
                "CHECKOUT_FAILED",
                "cart:" + cartId,
                AuditResult.FAILURE,
                cause == null ? "Checkout failed" : cause.getMessage(),
                details,
                "checkout-" + cartId,
                null,
                null);
    }
}
