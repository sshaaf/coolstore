package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.audit.logging.model.AuditEvent;
import com.redhat.coolstore.audit.AuditService;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Cart lifecycle and pricing characterisation. Uses hand-rolled fakes so the test runs without a
 * container, a database or a broker.
 */
class ShoppingCartServiceTest {

    private static final Map<String, Double> PRICES = new HashMap<>();

    static {
        PRICES.put("329299", 10.0); // promoted: 25% off
        PRICES.put("329199", 9.0);
        PRICES.put("165613", 4.15);
    }

    private FakeProductService productService;
    private RecordingOrderProcessor orderProcessor;
    private RecordingAuditService auditService;
    private ShoppingCartService cartService;

    @BeforeEach
    void setUp() {
        productService = new FakeProductService();
        orderProcessor = new RecordingOrderProcessor();
        auditService = new RecordingAuditService();
        cartService = new ShoppingCartService(productService, new PromoService(), orderProcessor,
                new ShippingService(), new InMemoryCartStore(1800), auditService);
    }

    @Test
    @DisplayName("priceShoppingCart recomputes totals, promotion savings, shipping and insurance")
    void priceShoppingCart() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item("329299", 2));
        cart.addShoppingCartItem(item("329199", 1));

        cartService.priceShoppingCart(cart);

        // 2 * (10 * 0.75) + 1 * 9 = 24.00 -> shipping band 0-25 = 2.99, no insurance below 25
        assertEquals(24.0, cart.getCartItemTotal(), 0.0000001);
        assertEquals(-5.0, cart.getCartItemPromoSavings(), 0.0000001);
        assertEquals(2.99, cart.getShippingTotal(), 0.0000001);
        assertEquals(0.0, cart.getShippingPromoSavings(), 0.0000001);
        assertEquals(26.99, cart.getCartTotal(), 0.0000001);
    }

    @Test
    @DisplayName("Above 75 the shipping is free and the discount records the waived fee")
    void priceShoppingCartWithFreeShipping() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item("329299", 100)); // 750.00 after promotion

        cartService.priceShoppingCart(cart);

        assertEquals(750.0, cart.getCartItemTotal(), 0.0000001);
        // 10.99 shipping + 7.50 insurance, then waived by the >= 75 promotion
        assertEquals(0.0, cart.getShippingTotal(), 0.0);
        assertEquals(-18.49, cart.getShippingPromoSavings(), 0.0000001);
        assertEquals(750.0, cart.getCartTotal(), 0.0000001);
    }

    @Test
    void priceShoppingCartIsIdempotent() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item("329299", 1));
        cartService.priceShoppingCart(cart);
        double firstTotal = cart.getCartTotal();
        cartService.priceShoppingCart(cart);
        assertEquals(firstTotal, cart.getCartTotal(), 0.0000001);
    }

    @Test
    @DisplayName("Adding the same product twice merges the quantities into one line")
    void addSameProductTwiceDedupes() {
        cartService.addItem("cart-1", "329299", 1);
        ShoppingCart cart = cartService.addItem("cart-1", "329299", 2);

        assertEquals(1, cart.getShoppingCartItemList().size());
        assertEquals(3, cart.getShoppingCartItemList().get(0).getQuantity());
        assertEquals(22.5, cart.getCartItemTotal(), 0.0000001); // 3 * 7.5
    }

    @Test
    void twoCartsAreIsolated() {
        cartService.addItem("cart-a", "329299", 1);
        cartService.addItem("cart-b", "329199", 2);

        ShoppingCart a = cartService.getShoppingCart("cart-a");
        ShoppingCart b = cartService.getShoppingCart("cart-b");

        assertEquals(1, a.getShoppingCartItemList().size());
        assertEquals("329299", a.getShoppingCartItemList().get(0).getProduct().getItemId());
        assertEquals(1, b.getShoppingCartItemList().size());
        assertEquals("329199", b.getShoppingCartItemList().get(0).getProduct().getItemId());
        assertNotSame(a, b);
    }

    @Test
    @DisplayName("Deleting more than the line quantity removes the line, less decrements it")
    void deleteSemantics() {
        cartService.addItem("cart-1", "329299", 5);

        ShoppingCart afterPartialDelete = cartService.deleteItem("cart-1", "329299", 2);
        assertEquals(3, afterPartialDelete.getShoppingCartItemList().get(0).getQuantity());

        ShoppingCart afterFullDelete = cartService.deleteItem("cart-1", "329299", 3);
        assertTrue(afterFullDelete.getShoppingCartItemList().isEmpty());
        assertEquals(0.0, afterFullDelete.getCartTotal(), 0.0);
    }

    @Test
    @DisplayName("setCart transfers the anonymous cart contents without aliasing the list")
    void setCartTransfersItems() {
        cartService.addItem("tmp-42", "329299", 2);

        ShoppingCart authenticated = cartService.setCart("user-7", "tmp-42");

        assertEquals(1, authenticated.getShoppingCartItemList().size());
        assertEquals(2, authenticated.getShoppingCartItemList().get(0).getQuantity());

        // mutating the source cart must not change the target cart
        ShoppingCart tmp = cartService.getShoppingCart("tmp-42");
        assertNotSame(tmp.getShoppingCartItemList(), authenticated.getShoppingCartItemList());
        tmp.getShoppingCartItemList().clear();
        assertEquals(1, cartService.getShoppingCart("user-7").getShoppingCartItemList().size());
    }

    @Test
    @DisplayName("Checkout publishes exactly one event, clears the items and re-prices")
    void checkoutPublishesAndClears() {
        cartService.addItem("cart-1", "329299", 3);

        ShoppingCart cart = cartService.checkOutShoppingCart("cart-1");

        assertEquals(1, orderProcessor.payloads.size());
        assertTrue(orderProcessor.payloads.get(0).contains("\"productSku\":\"329299\""));
        assertTrue(orderProcessor.payloads.get(0).contains("\"quantity\":3"));
        assertTrue(cart.getShoppingCartItemList().isEmpty());
        assertEquals(0.0, cart.getCartItemTotal(), 0.0);
        assertEquals(0.0, cart.getCartTotal(), 0.0);
        assertEquals(1, auditService.events.size());
        assertEquals("CHECKOUT_SUBMITTED", auditService.events.get(0).action());
    }

    @Test
    void unknownItemIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> cartService.addItem("cart-1", "does-not-exist", 1));
        assertTrue(cartService.getShoppingCart("cart-1").getShoppingCartItemList().isEmpty());
    }

    @Test
    void emptyCartPricesToZero() {
        ShoppingCart cart = cartService.getShoppingCart("fresh");
        cartService.priceShoppingCart(cart);
        assertEquals(0.0, cart.getCartTotal(), 0.0);
        assertEquals(0.0, cart.getShippingTotal(), 0.0);
    }

    @Test
    void storeReturnsTheSameCartInstancePerId() {
        CartStore store = new InMemoryCartStore(1800);
        ShoppingCart first = store.get("id");
        ShoppingCart second = store.get("id");
        assertSame(first, second);
        store.remove("id");
        assertNotSame(first, store.get("id"));
    }

    private static ShoppingCartItem item(String itemId, int quantity) {
        Product p = new Product();
        p.setItemId(itemId);
        p.setPrice(PRICES.get(itemId));
        ShoppingCartItem sci = new ShoppingCartItem();
        sci.setProduct(p);
        sci.setPrice(p.getPrice());
        sci.setQuantity(quantity);
        return sci;
    }

    static class FakeProductService extends ProductService {
        @Override
        public Product getProductByItemId(String itemId) {
            Double price = PRICES.get(itemId);
            if (price == null) {
                return null;
            }
            Product p = new Product();
            p.setItemId(itemId);
            p.setName("Product " + itemId);
            p.setPrice(price);
            p.setQuantity(100);
            p.setLocation("Raleigh");
            return p;
        }

        @Override
        public List<Product> getProducts() {
            List<Product> products = new ArrayList<>();
            PRICES.keySet().forEach(id -> products.add(getProductByItemId(id)));
            return products;
        }
    }

    static class RecordingOrderProcessor extends ShoppingCartOrderProcessor {
        final List<String> payloads = new ArrayList<>();

        @Override
        public void process(ShoppingCart cart) {
            payloads.add(com.redhat.coolstore.utils.Transformers.shoppingCartToJson(cart, "Test User", "test@example.com"));
        }
    }

    static class RecordingAuditService extends AuditService {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void emit(AuditEvent event) {
            events.add(event);
        }
    }
}
