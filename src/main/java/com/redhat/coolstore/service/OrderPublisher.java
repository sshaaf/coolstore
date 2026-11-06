package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Thin adapter over the reactive messaging {@link Emitter}.
 *
 * <p>It exists so the checkout path can be tested without a broker: tests substitute an
 * {@code @Alternative} publisher that records the payload, while production always goes through
 * the {@code orders-out} channel (Kafka topic {@code orders}).</p>
 */
@ApplicationScoped
public class OrderPublisher {

    @Inject
    @Channel("orders-out")
    Emitter<String> ordersEmitter;

    public void publish(String orderJson) {
        ordersEmitter.send(orderJson);
    }
}
