package com.redhat.coolstore.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import com.redhat.coolstore.service.CartStore;
import com.redhat.coolstore.service.CatalogService;
import com.redhat.coolstore.service.OrderConsumer;
import com.redhat.coolstore.service.OrderPublisher;
import com.redhat.coolstore.service.OrderService;
import com.redhat.coolstore.service.ShoppingCartService;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Messaging parity (Story 5 tasks 5.4/5.5/5.8).
 *
 * <p>The outgoing side is verified through an {@code @Alternative} {@link OrderPublisher} that
 * records exactly what the checkout path would put on the broker; the incoming side calls the real
 * {@link OrderConsumer#consume(String)} method, and a reflection assertion pins the
 * {@code @Incoming("orders")} wiring. No broker or external mock library is required.</p>
 */
@QuarkusTest
class OrderMessagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records what the application publishes on the {@code orders-out} channel. */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class RecordingOrderPublisher extends OrderPublisher {
        static final List<String> PUBLISHED = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String orderJson) {
            PUBLISHED.add(orderJson);
        }
    }

    @Inject
    ShoppingCartService shoppingCartService;

    @Inject
    CartStore cartStore;

    @Inject
    CatalogService catalogService;

    @Inject
    OrderService orderService;

    @Inject
    OrderConsumer orderConsumer;

    @BeforeEach
    void resetRecorder() {
        RecordingOrderPublisher.PUBLISHED.clear();
    }

    private record PricedCart(String cartId, ShoppingCart cart) {
    }

    private PricedCart cartWith(String itemId, int quantity) {
        String cartId = "msg-" + UUID.randomUUID();
        ShoppingCart cart = cartStore.getOrCreate(cartId);
        cart.resetShoppingCartItemList();

        Product product = shoppingCartService.getProduct(itemId);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPrice(product.getPrice());
        cart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(cart);
        return new PricedCart(cartId, cart);
    }

    private static String orderJson(String sku, int quantity, String customer) {
        return "{\"orderValue\":20.0,\"customerName\":\"" + customer + "\",\"customerEmail\":\""
                + customer + "@example.com\",\"retailPrice\":20.0,\"discount\":0.0,"
                + "\"shippingFee\":2.99,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}";
    }

    @Test
    @DisplayName("@Incoming(\"orders\") is the consumer channel")
    void consumerIsBoundToOrdersChannel() throws Exception {
        Method consume = OrderConsumer.class.getMethod("consume", String.class);
        Incoming incoming = consume.getAnnotation(Incoming.class);
        assertNotNull(incoming, "OrderConsumer.consume must be annotated with @Incoming");
        assertEquals("orders", incoming.value());
    }

    @Test
    @DisplayName("checkout publishes the documented JSON document on the orders contract")
    void checkoutPublishesDocumentedPayload() throws Exception {
        PricedCart cart = cartWith("329299", 2);

        shoppingCartService.checkOutShoppingCart(cart.cartId());

        assertEquals(1, RecordingOrderPublisher.PUBLISHED.size(), "exactly one order document per checkout");

        JsonNode node = MAPPER.readTree(RecordingOrderPublisher.PUBLISHED.get(0));
        assertEquals(17.99, node.get("orderValue").asDouble(), 0.0001);
        assertEquals(15.0, node.get("retailPrice").asDouble(), 0.0001);
        assertEquals(-5.0, node.get("discount").asDouble(), 0.0001);
        assertEquals(2.99, node.get("shippingFee").asDouble(), 0.0001);
        assertEquals(0.0, node.get("shippingDiscount").asDouble(), 0.0001);
        assertNotNull(node.get("customerName"));
        assertNotNull(node.get("customerEmail"));
        assertEquals(1, node.get("items").size());
        assertEquals("329299", node.get("items").get(0).get("productSku").asText());
        assertEquals(2, node.get("items").get(0).get("quantity").asInt());
    }

    @Test
    @DisplayName("checkout empties the cart after publishing")
    void checkoutResetsTheCart() {
        PricedCart cart = cartWith("444435", 1);

        ShoppingCart afterCheckout = shoppingCartService.checkOutShoppingCart(cart.cartId());

        assertTrue(afterCheckout.getShoppingCartItemList().isEmpty());
        assertEquals(0.0, afterCheckout.getCartTotal(), 0.0001);
        assertEquals(1, RecordingOrderPublisher.PUBLISHED.size());
    }

    @Test
    @DisplayName("the orders consumer persists the order and decrements inventory")
    void consumerPersistsAndDecrements() {
        String sku = "444437";
        int before = quantityOf(sku);
        int ordersBefore = orderService.getOrders().size();
        String customer = "Consumer-" + UUID.randomUUID();

        orderConsumer.consume(orderJson(sku, 3, customer));

        assertEquals(before - 3, quantityOf(sku), "inventory must be decremented by 3");
        assertEquals(ordersBefore + 1, orderService.getOrders().size(), "order must be persisted");

        Order persisted = orderService.getOrders().stream()
                .filter(o -> customer.equals(o.getCustomerName()))
                .findFirst().orElseThrow();
        assertEquals(1, persisted.getItemList().size());
        assertEquals(sku, persisted.getItemList().get(0).getProductId());
        assertEquals(3, persisted.getItemList().get(0).getQuantity());
    }

    @Test
    @DisplayName("a duplicate delivery is ignored: inventory is decremented once")
    void duplicateDeliveryIsIdempotent() {
        String sku = "165954";
        int before = quantityOf(sku);
        int ordersBefore = orderService.getOrders().size();
        String payload = orderJson(sku, 2, "Duplicate-" + UUID.randomUUID());

        orderConsumer.consume(payload);
        orderConsumer.consume(payload);

        assertEquals(before - 2, quantityOf(sku), "the duplicate must not decrement inventory twice");
        assertEquals(ordersBefore + 1, orderService.getOrders().size(), "the duplicate must not create a second order");
    }

    private int quantityOf(String sku) {
        return catalogService.getCatalogItemById(sku).getInventory().getQuantity();
    }
}
