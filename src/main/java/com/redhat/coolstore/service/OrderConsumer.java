package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

/**
 * Order consumer on the {@code orders} channel.
 *
 * <p>Replaces the {@code @MessageDriven} bean (destination {@code topic/orders},
 * {@code Auto-acknowledge}). The processing steps are unchanged and in the same order:</p>
 * <ol>
 *   <li>{@link Transformers#jsonToOrder(String)} parses the checkout document,</li>
 *   <li>{@code orderService.save(order)} persists {@code ORDERS}/{@code ORDER_ITEMS},</li>
 *   <li>every line item decrements {@code INVENTORY.quantity}
 *       ({@link CatalogService#updateInventoryItems(String, int)}).</li>
 * </ol>
 *
 * <p>Two differences, both required by the target platform:</p>
 * <ul>
 *   <li>the JMS {@code TextMessage} envelope is replaced by the raw JSON {@code String} — the wire
 *       payload is identical;</li>
 *   <li>duplicate deliveries are filtered by {@link OrderMessageDeduplicator} so a redelivery does
 *       not double-decrement inventory.</li>
 * </ul>
 */
@ApplicationScoped
public class OrderConsumer {

    private static final Logger LOG = Logger.getLogger(OrderConsumer.class);

    @Inject
    OrderService orderService;

    @Inject
    CatalogService catalogService;

    @Inject
    OrderMessageDeduplicator deduplicator;

    @Incoming("orders")
    public void consume(String orderJson) {
        LOG.info("Message recd !");
        LOG.infof("Received order: %s", orderJson);

        if (!deduplicator.claim(orderJson)) {
            LOG.warnf("Duplicate order delivery ignored: %s", orderJson);
            return;
        }

        Order order = Transformers.jsonToOrder(orderJson);
        LOG.infof("Order object is %s", order);
        orderService.save(order);
        order.getItemList().forEach(orderItem ->
                catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity()));
    }

}
