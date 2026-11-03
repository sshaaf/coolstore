package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

/**
 * Persists an incoming order and decrements inventory.
 *
 * <p>Replaces the {@code @MessageDriven} {@code OrderServiceMDB}, which listened on
 * {@code topic/orders}. It consumes the {@code orders-in} channel (consumer group
 * {@code coolstore-order-service}).</p>
 */
@ApplicationScoped
public class OrderMessageConsumer {

    @Inject
    Logger log;

    @Inject
    OrderService orderService;

    @Inject
    CatalogService catalogService;

    @Incoming("orders-in")
    @Transactional
    public void onMessage(String orderStr) {
        log.info("\nMessage recd !");
        log.infof("Received order: %s", orderStr);
        Order order = Transformers.jsonToOrder(orderStr);
        log.infof("Order object is %s", order);
        orderService.save(order);
        order.getItemList().forEach(orderItem ->
                catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity()));
    }
}
