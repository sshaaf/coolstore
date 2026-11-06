package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Characterization tests for promotion rules (Story 1, task 1.4).
 */
class PromoServiceTest {

    private final PromoService promoService = new PromoService();

    private static ShoppingCartItem item(String itemId, double price, int quantity) {
        Product product = new Product();
        product.setItemId(itemId);
        product.setPrice(price);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(price);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    @DisplayName("item 329299 gets 25 % off, everything else is untouched")
    void cartItemPromotion() {
        ShoppingCart cart = new ShoppingCart();
        ShoppingCartItem promoted = item("329299", 10.00, 3);
        ShoppingCartItem plain = item("444435", 13.00, 1);
        cart.setShoppingCartItemList(List.of(promoted, plain));

        promoService.applyCartItemPromotions(cart);

        assertEquals(7.50, promoted.getPrice(), 0.0001);
        assertEquals(-2.50, promoted.getPromoSavings(), 0.0001);
        assertEquals(13.00, plain.getPrice(), 0.0001);
        assertEquals(0.0, plain.getPromoSavings(), 0.0001);
    }

    @Test
    @DisplayName("shipping is free from a 75 cart item total")
    void shippingPromotion() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(75.0);
        cart.setShippingTotal(8.99);

        promoService.applyShippingPromotions(cart);

        assertEquals(0.0, cart.getShippingTotal(), 0.0001);
        assertEquals(-8.99, cart.getShippingPromoSavings(), 0.0001);
    }

    @Test
    @DisplayName("shipping is charged below a 75 cart item total")
    void shippingNotPromotedBelowThreshold() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(74.99);
        cart.setShippingTotal(6.99);

        promoService.applyShippingPromotions(cart);

        assertEquals(6.99, cart.getShippingTotal(), 0.0001);
        assertEquals(0.0, cart.getShippingPromoSavings(), 0.0001);
    }

    @Test
    @DisplayName("null and empty carts are ignored")
    void nullAndEmptyCarts() {
        promoService.applyCartItemPromotions(null);
        ShoppingCart empty = new ShoppingCart();
        promoService.applyCartItemPromotions(empty);
        promoService.applyShippingPromotions(empty);
        assertEquals(0.0, empty.getShippingTotal(), 0.0001);
    }

    @Test
    @DisplayName("the promotion set is defensive-copied")
    void promotionsAreCopied() {
        assertEquals(1, promoService.getPromotions().size());
        assertTrue(promoService.getPromotions().stream().anyMatch(p -> "329299".equals(p.getItemId())));
    }
}
