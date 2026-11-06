package com.redhat.coolstore.service;

import com.redhat.coolstore.model.ShoppingCart;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingServiceTest {

    private final ShippingService shippingService = new ShippingService();

    private static ShoppingCart cartWithTotal(double total) {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(total);
        return cart;
    }

    @Test
    void shippingBracketsMatchLegacyBehaviour() {
        assertThat(shippingService.calculateShipping(cartWithTotal(10))).isEqualTo(2.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(30))).isEqualTo(4.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(60))).isEqualTo(6.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(80))).isEqualTo(8.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(150))).isEqualTo(10.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(20000))).isEqualTo(0.0);
    }

    @Test
    void bracketBoundariesAreInclusiveAtTheLowerEdge() {
        assertThat(shippingService.calculateShipping(cartWithTotal(0))).isEqualTo(2.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(24.99))).isEqualTo(2.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(25))).isEqualTo(4.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(50))).isEqualTo(6.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(75))).isEqualTo(8.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(100))).isEqualTo(10.99);
        assertThat(shippingService.calculateShipping(cartWithTotal(9999.99))).isEqualTo(10.99);
    }

    @Test
    void nullCartIsZero() {
        assertThat(shippingService.calculateShipping(null)).isEqualTo(0.0);
        assertThat(shippingService.calculateShippingInsurance(null)).isEqualTo(0.0);
    }

    @Test
    void insuranceTiersUseHalfUpRounding() {
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(30))).isEqualTo(0.60);
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(200))).isEqualTo(3.00);
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(800))).isEqualTo(8.00);
        // 33 * 0.02 = 0.66; 25.555 -> HALF_UP at 2dp
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(33))).isEqualTo(0.66);
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(25))).isEqualTo(0.50);
    }

    @Test
    void insuranceIsOnlyChargedBetween25And10000() {
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(10))).isEqualTo(0.0);
        assertThat(shippingService.calculateShippingInsurance(cartWithTotal(10000))).isEqualTo(0.0);
    }
}
