package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

/**
 * Low-stock notifier - the useful half of the legacy {@code InventoryNotificationMDB}.
 *
 * <p>The monolith class was dead code: it implemented {@code MessageListener} but was never annotated
 * {@code @MessageDriven}, and it opened a WebLogic T3 connection ({@code t3://localhost:7001}). The
 * business rule (warn when the projected stock drops below 50) is kept here as a second, independent
 * consumer of the order event - giving the JMS-topic fan-out semantics back without any vendor API.
 */
@ApplicationScoped
public class InventoryNotificationConsumer {

    private static final Logger LOG = Logger.getLogger(InventoryNotificationConsumer.class);

    static final int LOW_THRESHOLD = 50;

    @Inject
    CatalogService catalogService;

    public InventoryNotificationConsumer() {
    }

    public InventoryNotificationConsumer(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Incoming("orders-inventory")
    public void onOrder(String orderJson) {
        if (orderJson == null || orderJson.isBlank()) {
            return;
        }
        try {
            Order order = Transformers.jsonToOrder(orderJson);
            order.getItemList().forEach(orderItem -> {
                CatalogItemEntity item = catalogService.getCatalogItemById(orderItem.getProductId());
                if (item == null || item.getInventory() == null) {
                    return;
                }
                int newQuantity = item.getInventory().getQuantity() - orderItem.getQuantity();
                if (newQuantity < LOW_THRESHOLD) {
                    LOG.warnf("Inventory for item %s is below threshold (%d), contact supplier!",
                            orderItem.getProductId(), LOW_THRESHOLD);
                }
            });
        } catch (RuntimeException ex) {
            LOG.errorf(ex, "Could not evaluate low-stock notification for: %s", orderJson);
        }
    }
}
