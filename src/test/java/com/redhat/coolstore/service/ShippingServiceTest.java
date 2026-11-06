package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Characterisation of the shipping arithmetic. Every boundary of the legacy bands is asserted to the
 * cent; these values must not change during the migration.
 */
class ShippingServiceTest {

    private final ShippingService shippingService = new ShippingService();

    private static ShoppingCart cartWithTotal(double cartItemTotal) {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(cartItemTotal);
        return cart;
    }

    @ParameterizedTest(name = "cartItemTotal={0} -> shipping {1}")
    @CsvSource({
            "0,      2.99",
            "24.99,  2.99",
            "25,     4.99",
            "49.99,  4.99",
            "50,     6.99",
            "74.99,  6.99",
            "75,     8.99",
            "99.99,  8.99",
            "100,    10.99",
            "499.99, 10.99",
            "9999.99, 10.99",
            "10000,  0.00"
    })
    @DisplayName("calculateShipping keeps the legacy band boundaries")
    void calculateShippingBands(double cartItemTotal, double expected) {
        assertEquals(expected, shippingService.calculateShipping(cartWithTotal(cartItemTotal)), 0.0000001);
    }

    @Test
    void calculateShippingIsZeroForNullCart() {
        assertEquals(0.0, shippingService.calculateShipping(null), 0.0);
    }

    @ParameterizedTest(name = "cartItemTotal={0} -> insurance {1}")
    @CsvSource({
            "0,      0.00",
            "24.99,  0.00",
            "25,     0.50",
            "99.99,  2.00",
            "100,    1.50",
            "499.99, 7.50",
            "500,    5.00",
            "9999.99, 100.00",
            "10000,  0.00"
    })
    @DisplayName("calculateShippingInsurance rounds HALF_UP to 2 decimals")
    void calculateShippingInsuranceBands(double cartItemTotal, double expected) {
        assertEquals(expected, shippingService.calculateShippingInsurance(cartWithTotal(cartItemTotal)), 0.0000001);
    }

    @Test
    void calculateShippingInsuranceIsZeroForNullCart() {
        assertEquals(0.0, shippingService.calculateShippingInsurance(null), 0.0);
    }
}
