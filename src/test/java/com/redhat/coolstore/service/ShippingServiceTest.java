package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Characterization tests for the shipping and insurance tables (Story 1, task 1.4).
 * These numbers are the contract: they must not change during the migration.
 */
class ShippingServiceTest {

    private final ShippingService shippingService = new ShippingService();

    private static ShoppingCart cartWithTotal(double total) {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(total);
        return cart;
    }

    @ParameterizedTest(name = "cart total {0} -> shipping {1}")
    @CsvSource({
            "0.0, 2.99",
            "10.0, 2.99",
            "24.99, 2.99",
            "25.0, 4.99",
            "49.99, 4.99",
            "50.0, 6.99",
            "74.99, 6.99",
            "75.0, 8.99",
            "99.99, 8.99",
            "100.0, 10.99",
            "9999.99, 10.99",
            "10000.0, 0.0",
            "-1.0, 0.0"
    })
    @DisplayName("shipping fee tiers are unchanged")
    void shippingTiers(double cartItemTotal, double expected) {
        assertEquals(expected, shippingService.calculateShipping(cartWithTotal(cartItemTotal)), 0.0001);
    }

    @ParameterizedTest(name = "cart total {0} -> insurance {1}")
    @CsvSource({
            "0.0, 0.0",
            "10.0, 0.0",
            "24.99, 0.0",
            "25.0, 0.50",
            "50.0, 1.00",
            "99.99, 2.00",
            "100.0, 1.50",
            "400.0, 6.00",
            "499.99, 7.50",
            "500.0, 5.00",
            "1000.0, 10.00",
            "9999.99, 100.00",
            "10000.0, 0.0"
    })
    @DisplayName("insurance tiers (2 % / 1.5 % / 1 %) are unchanged")
    void insuranceTiers(double cartItemTotal, double expected) {
        assertEquals(expected, shippingService.calculateShippingInsurance(cartWithTotal(cartItemTotal)), 0.0001);
    }

    @Test
    @DisplayName("insurance rounds HALF_UP at scale 2")
    void insuranceRoundsHalfUp() {
        // 33.33 * 2 % = 0.6666 -> 0.67
        assertEquals(0.67, shippingService.calculateShippingInsurance(cartWithTotal(33.33)), 0.0001);
        // 125.05 * 1.5 % = 1.87575 -> 1.88
        assertEquals(1.88, shippingService.calculateShippingInsurance(cartWithTotal(125.05)), 0.0001);
        // 512.05 * 1 % = 5.1205 -> 5.12
        assertEquals(5.12, shippingService.calculateShippingInsurance(cartWithTotal(512.05)), 0.0001);
    }

    @Test
    @DisplayName("null cart yields zero for both calculations")
    void nullCart() {
        assertEquals(0.0, shippingService.calculateShipping(null), 0.0001);
        assertEquals(0.0, shippingService.calculateShippingInsurance(null), 0.0001);
    }
}
