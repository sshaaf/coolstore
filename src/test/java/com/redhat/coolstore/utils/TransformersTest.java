package com.redhat.coolstore.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/** Pins the order wire format that the JMS topic used to carry. */
class TransformersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Cart to JSON keeps every field the consumers rely on")
    void cartToJson() throws Exception {
        Product product = new Product();
        product.setItemId("329299");
        product.setPrice(7.50);
        ShoppingCartItem sci = new ShoppingCartItem();
        sci.setProduct(product);
        sci.setPrice(7.50);
        sci.setQuantity(2);

        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(sci));
        cart.setCartItemPromoSavings(-5.00);
        cart.setShippingTotal(2.99);
        cart.setShippingPromoSavings(0.0);
        cart.setCartTotal(17.99);

        JsonNode json = mapper.readTree(Transformers.shoppingCartToJson(cart));

        assertEquals(17.99, json.get("orderValue").asDouble(), 0.0001);
        assertEquals(-5.00, json.get("discount").asDouble(), 0.0001);
        assertEquals(15.00, json.get("retailPrice").asDouble(), 0.0001);
        assertEquals(2.99, json.get("shippingFee").asDouble(), 0.0001);
        assertTrue(json.hasNonNull("customerName"));
        assertTrue(json.hasNonNull("customerEmail"));
        assertEquals("329299", json.get("items").get(0).get("productSku").asText());
        assertEquals(2, json.get("items").get(0).get("quantity").asInt());
    }

    @Test
    @DisplayName("JSON to order round-trips the payload")
    void jsonToOrder() {
        String payload = """
                {"orderValue":10.49,"customerName":"Karl Svensson","customerEmail":"karl@gmail.com",
                 "retailPrice":10.0,"discount":-2.5,"shippingFee":2.99,"shippingDiscount":0.0,
                 "items":[{"productSku":"329299","quantity":1}]}
                """;

        Order order = Transformers.jsonToOrder(payload);

        assertNotNull(order);
        assertEquals("Karl Svensson", order.getCustomerName());
        assertEquals("karl@gmail.com", order.getCustomerEmail());
        assertEquals(10.49, order.getOrderValue(), 0.0001);
        assertEquals(-2.5, order.getDiscount(), 0.0001);
        assertEquals(1, order.getItemList().size());
        OrderItem item = order.getItemList().get(0);
        assertEquals("329299", item.getProductId());
        assertEquals(1, item.getQuantity());
    }

    @Test
    @DisplayName("Invalid payload fails fast with a descriptive error")
    void invalidPayload() {
        try {
            Transformers.jsonToOrder("not-json");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Cannot deserialize order payload"));
        }
    }
}
