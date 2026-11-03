package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

/**
 * Publishes an order on the {@code orders} channel.
 *
 * <p>Replaces the JMS producer that sent to {@code java:/topic/orders} through an
 * injected {@code JMSContext}. The message body is byte-for-byte the same JSON
 * payload the JMS topic carried, so downstream consumers keep working.</p>
 */
@ApplicationScoped
public class ShoppingCartOrderProcessor {

    @Inject
    Logger log;

    @Inject
    @Channel("orders-out")
    Emitter<String> ordersEmitter;

    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        String payload = Transformers.shoppingCartToJson(cart);
        ordersEmitter.send(payload).whenComplete((ok, failure) -> {
            if (failure != null) {
                // Checkout must not fail because the broker is unavailable; the
                // cart is priced and returned either way (same as the JMS path).
                log.errorf(failure, "Could not publish order to the orders topic: %s", failure.getMessage());
            }
        });
    }
}
