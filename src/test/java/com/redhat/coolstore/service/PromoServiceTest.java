package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

class PromoServiceTest {

    private final PromoService promoService = new PromoService();

    private static ShoppingCartItem item(String itemId, double price, int quantity) {
        Product product = new Product();
        product.setItemId(itemId);
        product.setPrice(price);
        ShoppingCartItem sci = new ShoppingCartItem();
        sci.setProduct(product);
        sci.setPrice(price);
        sci.setQuantity(quantity);
        return sci;
    }

    @Test
    @DisplayName("Item 329299 keeps its 25% promotion")
    void itemPromotion() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(item("329299", 10.00, 2)));

        promoService.applyCartItemPromotions(cart);

        ShoppingCartItem sci = cart.getShoppingCartItemList().get(0);
        assertEquals(-2.50, sci.getPromoSavings(), 0.0001);
        assertEquals(7.50, sci.getPrice(), 0.0001);
    }

    @Test
    @DisplayName("Non-promoted items are untouched")
    void noPromotion() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(item("165613", 4.15, 1)));

        promoService.applyCartItemPromotions(cart);

        assertEquals(0.0, cart.getShoppingCartItemList().get(0).getPromoSavings(), 0.0001);
        assertEquals(4.15, cart.getShoppingCartItemList().get(0).getPrice(), 0.0001);
    }

    @Test
    @DisplayName("Free shipping above a 75.00 cart item total")
    void freeShipping() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(80);
        cart.setShippingTotal(8.99);

        promoService.applyShippingPromotions(cart);

        assertEquals(-8.99, cart.getShippingPromoSavings(), 0.0001);
        assertEquals(0.0, cart.getShippingTotal(), 0.0001);
    }

    @Test
    @DisplayName("Shipping is charged below the threshold")
    void paidShipping() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(74.99);
        cart.setShippingTotal(6.99);

        promoService.applyShippingPromotions(cart);

        assertEquals(0.0, cart.getShippingPromoSavings(), 0.0001);
        assertEquals(6.99, cart.getShippingTotal(), 0.0001);
    }
}
