package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Topic;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

/**
 * Publishes the order to the JMS topic. Destination name and message body are unchanged
 * from the EJB implementation so external consumers stay wire-compatible.
 */
@ApplicationScoped
public class ShoppingCartOrderProcessor {

    private static final Logger log = Logger.getLogger(ShoppingCartOrderProcessor.class.getName());

    private final ConnectionFactory connectionFactory;

    @ConfigProperty(name = "orders.topic", defaultValue = "topic/orders")
    String topicName;

    @Inject
    public ShoppingCartOrderProcessor(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        String payload = Transformers.shoppingCartToJson(cart);
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            Topic topic = context.createTopic(topicName);
            context.createProducer().send(topic, payload);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Unable to publish order to " + topicName, e);
            throw e;
        }
    }
}
