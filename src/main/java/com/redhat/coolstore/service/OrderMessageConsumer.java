package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces the EJB {@code @MessageDriven OrderServiceMDB} with an Artemis listener.
 *
 * <p>Acknowledgement semantics changed deliberately: the legacy MDB used
 * {@code Auto-acknowledge}; this consumer uses {@code CLIENT_ACKNOWLEDGE} and only
 * acknowledges after the order is persisted and inventory deducted, so a failure leaves
 * the message on the topic for redelivery and, after
 * {@code orders.consumer.max-delivery-attempts}, dead-lettering to {@code orders.dlq}.</p>
 */
@ApplicationScoped
public class OrderMessageConsumer {

    private static final Logger log = Logger.getLogger(OrderMessageConsumer.class.getName());

    private final ConnectionFactory connectionFactory;
    private final OrderService orderService;

    @ConfigProperty(name = "orders.topic", defaultValue = "topic/orders")
    String topicName;

    @ConfigProperty(name = "orders.consumer.enabled", defaultValue = "true")
    boolean enabled;

    private JMSContext context;

    @Inject
    public OrderMessageConsumer(ConnectionFactory connectionFactory, OrderService orderService) {
        this.connectionFactory = connectionFactory;
        this.orderService = orderService;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            log.info("Order consumer disabled by configuration");
            return;
        }
        try {
            context = connectionFactory.createContext(JMSContext.CLIENT_ACKNOWLEDGE);
            Topic topic = context.createTopic(topicName);
            context.createConsumer(topic).setMessageListener(this::onMessage);
            log.info("Order consumer subscribed to " + topicName);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Order consumer could not subscribe to " + topicName
                    + "; checkout will still publish messages", e);
            closeContext();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        closeContext();
    }

    /** Visible for tests: process one JMS message. */
    public void onMessage(Message message) {
        try {
            if (!(message instanceof TextMessage textMessage)) {
                log.warning("Ignoring non-text JMS message: " + (message == null ? "null" : message.getClass()));
                message.acknowledge();
                return;
            }
            String orderStr = textMessage.getText();
            log.info("Received order: " + orderStr);
            Order order = Transformers.jsonToOrder(orderStr);
            orderService.processOrder(order);
            message.acknowledge();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Order processing failed; message left unacknowledged for redelivery", e);
        }
    }

    private void closeContext() {
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "Error closing JMS context", e);
            } finally {
                context = null;
            }
        }
    }
}
