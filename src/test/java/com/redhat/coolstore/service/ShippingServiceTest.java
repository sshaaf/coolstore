package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Characterization tests for the shipping rules lifted from the Java EE monolith.
 * The bands and rounding are customer-visible behaviour and must not drift.
 */
class ShippingServiceTest {

    private final ShippingService shippingService = new ShippingService();

    private static ShoppingCart cartWithTotal(double total) {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(total);
        return cart;
    }

    @Test
    @DisplayName("Shipping fee bands are preserved")
    void shippingBands() {
        assertEquals(2.99, shippingService.calculateShipping(cartWithTotal(0)));
        assertEquals(2.99, shippingService.calculateShipping(cartWithTotal(24.99)));
        assertEquals(4.99, shippingService.calculateShipping(cartWithTotal(25)));
        assertEquals(4.99, shippingService.calculateShipping(cartWithTotal(49.99)));
        assertEquals(6.99, shippingService.calculateShipping(cartWithTotal(50)));
        assertEquals(8.99, shippingService.calculateShipping(cartWithTotal(75)));
        assertEquals(10.99, shippingService.calculateShipping(cartWithTotal(100)));
        assertEquals(10.99, shippingService.calculateShipping(cartWithTotal(9999.99)));
        assertEquals(0.0, shippingService.calculateShipping(cartWithTotal(10000)));
    }

    @Test
    @DisplayName("Null cart never charges shipping")
    void nullCart() {
        assertEquals(0.0, shippingService.calculateShipping(null));
        assertEquals(0.0, shippingService.calculateShippingInsurance(null));
    }

    @Test
    @DisplayName("Insurance is 2% / 1.5% / 1% of the cart total, rounded HALF_UP")
    void insuranceBands() {
        assertEquals(0.0, shippingService.calculateShippingInsurance(cartWithTotal(24.99)));
        assertEquals(0.50, shippingService.calculateShippingInsurance(cartWithTotal(25)));
        assertEquals(1.00, shippingService.calculateShippingInsurance(cartWithTotal(50)));
        assertEquals(1.50, shippingService.calculateShippingInsurance(cartWithTotal(100)));
        assertEquals(3.00, shippingService.calculateShippingInsurance(cartWithTotal(200)));
        assertEquals(5.00, shippingService.calculateShippingInsurance(cartWithTotal(500)));
        assertEquals(80.0, shippingService.calculateShippingInsurance(cartWithTotal(8000)));
        assertEquals(0.0, shippingService.calculateShippingInsurance(cartWithTotal(10000)));
        assertEquals(0.07, shippingService.calculateShippingInsurance(cartWithTotal(25.01)));
    }
}
