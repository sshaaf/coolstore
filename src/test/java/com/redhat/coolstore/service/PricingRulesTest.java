package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Pricing orchestration characterization (Story 1 task 1.4, Story 5 task 5.8).
 *
 * <p>Exercises the real catalogue and the documented order of operations:
 * {@code initShoppingCartForPricing -> applyCartItemPromotions -> totals -> shipping -> insurance ->
 * applyShippingPromotions -> cartTotal}.</p>
 */
@QuarkusTest
class PricingRulesTest {

    @Inject
    ShoppingCartService shoppingCartService;

    @Inject
    CartStore cartStore;

    private ShoppingCart cartWith(String cartId, String itemId, int quantity) {
        ShoppingCart cart = cartStore.getOrCreate(cartId);
        cart.resetShoppingCartItemList();
        Product product = shoppingCartService.getProduct(itemId);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPrice(product.getPrice());
        cart.addShoppingCartItem(item);
        return cart;
    }

    private static String newCartId() {
        return "pricing-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("3 x Quarkus T-shirt: 25 % promo, tier-1 shipping, no insurance below 25")
    void promotedItemBelowInsuranceThreshold() {
        ShoppingCart cart = cartWith(newCartId(), "329299", 3);

        shoppingCartService.priceShoppingCart(cart);

        assertEquals(22.5, cart.getCartItemTotal(), 0.0001);
        assertEquals(-7.5, cart.getCartItemPromoSavings(), 0.0001);
        assertEquals(2.99, cart.getShippingTotal(), 0.0001);
        assertEquals(25.49, cart.getCartTotal(), 0.0001);
        assertEquals(0.0, cart.getShippingPromoSavings(), 0.0001);
    }

    @Test
    @DisplayName("5 x Quarkus twill cap: tier-3 shipping plus 2 % insurance, no free shipping")
    void insuranceAppliesAbove25() {
        ShoppingCart cart = cartWith(newCartId(), "444435", 5);

        shoppingCartService.priceShoppingCart(cart);

        assertEquals(65.0, cart.getCartItemTotal(), 0.0001);
        assertEquals(6.99 + 1.30, cart.getShippingTotal(), 0.0001);
        assertEquals(73.29, cart.getCartTotal(), 0.0001);
        assertEquals(0.0, cart.getShippingPromoSavings(), 0.0001);
    }

    @Test
    @DisplayName("6 x Quarkus twill cap: free shipping from 75, insurance waived by the promotion")
    void freeShippingAbove75() {
        ShoppingCart cart = cartWith(newCartId(), "444435", 6);

        shoppingCartService.priceShoppingCart(cart);

        assertEquals(78.0, cart.getCartItemTotal(), 0.0001);
        assertEquals(0.0, cart.getShippingTotal(), 0.0001);
        assertEquals(-10.55, cart.getShippingPromoSavings(), 0.0001);
        assertEquals(78.0, cart.getCartTotal(), 0.0001);
    }

    @Test
    @DisplayName("empty cart prices to zero")
    void emptyCart() {
        ShoppingCart cart = cartStore.getOrCreate(newCartId());

        shoppingCartService.priceShoppingCart(cart);

        assertEquals(0.0, cart.getCartItemTotal(), 0.0001);
        assertEquals(0.0, cart.getShippingTotal(), 0.0001);
        assertEquals(0.0, cart.getCartTotal(), 0.0001);
    }

    @Test
    @DisplayName("pricing is idempotent: re-pricing does not accumulate totals")
    void pricingIsIdempotent() {
        ShoppingCart cart = cartWith(newCartId(), "329299", 2);

        shoppingCartService.priceShoppingCart(cart);
        double firstTotal = cart.getCartTotal();
        List<ShoppingCartItem> itemsAfterFirstPass = List.copyOf(cart.getShoppingCartItemList());
        shoppingCartService.priceShoppingCart(cart);

        assertEquals(firstTotal, cart.getCartTotal(), 0.0001);
        assertEquals(itemsAfterFirstPass.size(), cart.getShoppingCartItemList().size());
    }

    @Test
    @DisplayName("carts are isolated by cartId (the old @Stateful bean shared one instance per session)")
    void cartsAreIsolatedByCartId() {
        String firstId = newCartId();
        String secondId = newCartId();

        cartWith(firstId, "329299", 1);
        ShoppingCart second = cartStore.getOrCreate(secondId);

        assertEquals(0, second.getShoppingCartItemList().size());
        assertEquals(1, shoppingCartService.getShoppingCart(firstId).getShoppingCartItemList().size());
    }
}
