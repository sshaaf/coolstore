package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

/**
 * Consumes checkout events: persists the order and decrements inventory.
 *
 * <p>Replaces the {@code @MessageDriven OrderServiceMDB} ({@code destinationLookup=topic/orders}).
 * Delivery is at-least-once; a malformed payload is logged and swallowed so the stream keeps running
 * (the JMS implementation threw a {@code RuntimeException} instead, which would redeliver/poison).
 */
@ApplicationScoped
public class OrderEventConsumer {

    private static final Logger LOG = Logger.getLogger(OrderEventConsumer.class);

    @Inject
    OrderService orderService;

    @Inject
    CatalogService catalogService;

    public OrderEventConsumer() {
    }

    public OrderEventConsumer(OrderService orderService, CatalogService catalogService) {
        this.orderService = orderService;
        this.catalogService = catalogService;
    }

    @Incoming("orders")
    public void consume(String orderJson) {
        if (orderJson == null || orderJson.isBlank()) {
            LOG.warn("Ignoring empty order event");
            return;
        }
        try {
            LOG.debugf("Received order: %s", orderJson);
            Order order = Transformers.jsonToOrder(orderJson);
            orderService.save(order);
            order.getItemList().forEach(orderItem ->
                    catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity()));
        } catch (RuntimeException ex) {
            LOG.errorf(ex, "Could not process order event, message dropped: %s", orderJson);
        }
    }
}
