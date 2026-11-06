package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.Promotion;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

class PromoServiceTest {

    private final PromoService promoService = new PromoService();

    private static ShoppingCartItem item(String itemId, double price, int quantity) {
        Product p = new Product();
        p.setItemId(itemId);
        p.setPrice(price);
        ShoppingCartItem sci = new ShoppingCartItem();
        sci.setProduct(p);
        sci.setPrice(price);
        sci.setQuantity(quantity);
        return sci;
    }

    @Test
    @DisplayName("The 329299 promotion gives 25% off and a negative promoSavings")
    void appliesItemPromotion() {
        ShoppingCart cart = new ShoppingCart();
        ShoppingCartItem sci = item("329299", 10.0, 2);
        cart.addShoppingCartItem(sci);

        promoService.applyCartItemPromotions(cart);

        assertEquals(7.5, sci.getPrice(), 0.0000001);
        assertEquals(-2.5, sci.getPromoSavings(), 0.0000001);
    }

    @Test
    void leavesNonPromotedItemsUnchanged() {
        ShoppingCart cart = new ShoppingCart();
        ShoppingCartItem sci = item("329199", 9.0, 1);
        cart.addShoppingCartItem(sci);

        promoService.applyCartItemPromotions(cart);

        assertEquals(9.0, sci.getPrice(), 0.0);
        assertEquals(0.0, sci.getPromoSavings(), 0.0);
    }

    @Test
    @DisplayName("Free shipping applies at cartItemTotal >= 75 only")
    void freeShippingFrom75() {
        ShoppingCart atBoundary = new ShoppingCart();
        atBoundary.setCartItemTotal(75.0);
        atBoundary.setShippingTotal(8.99);
        promoService.applyShippingPromotions(atBoundary);
        assertEquals(0.0, atBoundary.getShippingTotal(), 0.0);
        assertEquals(-8.99, atBoundary.getShippingPromoSavings(), 0.0);

        ShoppingCart belowBoundary = new ShoppingCart();
        belowBoundary.setCartItemTotal(74.99);
        belowBoundary.setShippingTotal(6.99);
        promoService.applyShippingPromotions(belowBoundary);
        assertEquals(6.99, belowBoundary.getShippingTotal(), 0.0);
        assertEquals(0.0, belowBoundary.getShippingPromoSavings(), 0.0);
    }

    @Test
    void promotionSetIsDefensiveCopy() {
        Set<Promotion> promotions = promoService.getPromotions();
        promotions.clear();
        assertEquals(1, promoService.getPromotions().size());

        promoService.setPromotions(Set.of(new Promotion("444434", 0.1)));
        assertEquals(1, promoService.getPromotions().size());
        assertTrue(promoService.getPromotions().stream().anyMatch(p -> "444434".equals(p.getItemId())));
    }

    @Test
    void nullCartIsIgnored() {
        promoService.applyCartItemPromotions(null);
        promoService.applyShippingPromotions(null);
    }
}
