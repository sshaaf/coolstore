package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

/**
 * Publishes a checkout as a JSON order on the {@code orders} contract.
 *
 * <p>Replaces the JMS producer ({@code @Inject JMSContext} + {@code @Resource(lookup =
 * "java:/topic/orders") Topic}). The payload is the byte-identical
 * {@link Transformers#shoppingCartToJson(ShoppingCart)} document that the JBoss deployment put on
 * {@code topic/orders}; only the transport changed (SmallRye Reactive Messaging). The outgoing
 * channel is {@code orders-out} and maps to the Kafka topic {@code orders}; SmallRye forbids one
 * channel name being both an emitter and a consumer, so producer and consumer sides are named
 * separately while the broker contract stays a single {@code orders} topic.</p>
 */
@ApplicationScoped
public class ShoppingCartOrderProcessor {

    @Inject
    Logger log;

    @Inject
    OrderPublisher orderPublisher;

    public void process(ShoppingCart cart) {
        String orderJson = Transformers.shoppingCartToJson(cart);
        log.info("Sending order from processor: ");
        orderPublisher.publish(orderJson);
    }

}
