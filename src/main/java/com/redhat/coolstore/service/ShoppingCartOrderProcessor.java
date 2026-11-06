package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

/**
 * Publishes the checkout order event.
 *
 * <p>Replaces the {@code @Stateless} bean that injected a {@code JMSContext} and sent to
 * the JBoss JMS topic address. The payload is unchanged (see
 * {@link Transformers#shoppingCartToJson(ShoppingCart)}); only the transport moved to SmallRye
 * Reactive Messaging over AMQP 1.0.
 */
@ApplicationScoped
public class ShoppingCartOrderProcessor {

    private static final Logger LOG = Logger.getLogger(ShoppingCartOrderProcessor.class);

    @Inject
    @Channel("orders")
    Emitter<String> ordersEmitter;

    public ShoppingCartOrderProcessor() {
    }

    public ShoppingCartOrderProcessor(Emitter<String> ordersEmitter) {
        this.ordersEmitter = ordersEmitter;
    }

    public void process(ShoppingCart cart) {
        String payload = Transformers.shoppingCartToJson(cart);
        LOG.info("Sending order from processor: " + payload);
        ordersEmitter.send(payload);
    }
}
