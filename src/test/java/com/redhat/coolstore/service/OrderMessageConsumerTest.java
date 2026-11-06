package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Order;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderMessageConsumerTest {

    private OrderService orderService;
    private OrderMessageConsumer consumer;

    private static final String PAYLOAD = """
            {"orderValue":17.99,"customerName":"A","customerEmail":"a@b.c","retailPrice":15.0,
             "discount":-5.0,"shippingFee":2.99,"shippingDiscount":0.0,
             "items":[{"productSku":"329299","quantity":2}]}
            """;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        consumer = new OrderMessageConsumer(mock(ConnectionFactory.class), orderService);
    }

    @Test
    void messageIsAcknowledgedOnlyAfterSuccessfulProcessing() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(PAYLOAD);

        consumer.onMessage(message);

        verify(orderService).processOrder(any(Order.class));
        verify(message).acknowledge();
    }

    @Test
    void failedProcessingLeavesTheMessageUnacknowledged() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(PAYLOAD);
        doThrow(new IllegalStateException("db down")).when(orderService).processOrder(any(Order.class));

        consumer.onMessage(message);

        verify(orderService).processOrder(any(Order.class));
        verify(message, never()).acknowledge();
    }

    @Test
    void malformedPayloadIsNotAcknowledged() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("{not json");

        consumer.onMessage(message);
        verify(orderService, never()).processOrder(any(Order.class));
        verify(message, never()).acknowledge();
    }

    @Test
    void nonTextMessageIsAcknowledgedAndSkipped() throws Exception {
        jakarta.jms.Message message = mock(jakarta.jms.Message.class);

        consumer.onMessage(message);

        verify(orderService, never()).processOrder(any(Order.class));
        verify(message).acknowledge();
    }
}
