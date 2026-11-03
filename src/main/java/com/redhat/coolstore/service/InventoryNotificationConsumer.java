package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.utils.Transformers;

/**
 * Low-stock notification handler.
 *
 * <p>The original {@code InventoryNotificationMDB} was never wired: it looked up a
 * WebLogic T3 {@code TopicConnectionFactory} on JBoss EAP and had no activation
 * config, so it never received a message. This consumer makes the intent real by
 * subscribing to the same {@code orders} topic through a second consumer group
 * ({@code coolstore-inventory-notification}) — a JMS topic delivers to every
 * subscriber, so both handlers must see each order.</p>
 */
@ApplicationScoped
public class InventoryNotificationConsumer {

    private static final int LOW_THRESHOLD = 50;

    @Inject
    Logger log;

    @Inject
    CatalogService catalogService;

    @Incoming("orders-inventory")
    public void onMessage(String orderStr) {
        Order order = Transformers.jsonToOrder(orderStr);
        for (OrderItem orderItem : order.getItemList()) {
            CatalogItemEntity catalogItem = catalogService.getCatalogItemById(orderItem.getProductId());
            if (catalogItem == null || catalogItem.getInventory() == null) {
                log.warnf("Inventory for item %s is unknown, cannot check threshold", orderItem.getProductId());
                continue;
            }
            int oldQuantity = catalogItem.getInventory().getQuantity();
            int newQuantity = oldQuantity - orderItem.getQuantity();
            if (newQuantity < LOW_THRESHOLD) {
                log.warnf("Inventory for item %s is below threshold (%d), contact supplier!",
                        orderItem.getProductId(), LOW_THRESHOLD);
            } else {
                orderItem.setQuantity(newQuantity);
            }
        }
    }
}
