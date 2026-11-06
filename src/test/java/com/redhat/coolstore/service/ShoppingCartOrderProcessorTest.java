package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingCartOrderProcessorTest {

    private JMSContext context;
    private JMSProducer producer;
    private Topic topic;
    private ShoppingCartOrderProcessor processor;

    @BeforeEach
    void setUp() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        context = mock(JMSContext.class);
        producer = mock(JMSProducer.class);
        topic = mock(Topic.class);
        when(connectionFactory.createContext(anyInt())).thenReturn(context);
        when(context.createTopic("topic/orders")).thenReturn(topic);
        when(context.createProducer()).thenReturn(producer);

        processor = new ShoppingCartOrderProcessor(connectionFactory);
        processor.topicName = "topic/orders";
    }

    private static ShoppingCart cart() {
        Product product = new Product();
        product.setItemId("329299");
        product.setPrice(7.50);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(7.50);
        item.setQuantity(2);
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item);
        cart.setCartTotal(17.99);
        return cart;
    }

    @Test
    void publishesTheLegacyJsonBodyToTopicOrders() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        processor.process(cart());

        verify(producer).send(eq(topic), payload.capture());
        assertThat(payload.getValue())
                .contains("\"productSku\":\"329299\"")
                .contains("\"quantity\":2")
                .contains("\"orderValue\":17.99");
    }

    @Test
    void brokerFailureIsPropagated() {
        doThrow(new jakarta.jms.JMSRuntimeException("broker down")).when(producer).send(any(Topic.class), any(String.class));

        assertThatThrownBy(() -> processor.process(cart()))
                .isInstanceOf(jakarta.jms.JMSRuntimeException.class);
    }
}
