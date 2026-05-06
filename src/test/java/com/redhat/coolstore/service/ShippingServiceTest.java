package com.redhat.coolstore.service;

import com.redhat.coolstore.model.ShoppingCart;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Functional tests for ShippingService.
 * Tests shipping cost calculation logic based on cart total thresholds.
 */
@QuarkusTest
public class ShippingServiceTest {

    @Inject
    ShippingService shippingService;

    private ShoppingCart testCart;

    @BeforeEach
    public void setup() {
        testCart = new ShoppingCart();
    }

    @Test
    public void testCalculateShippingForCartUnder25() {
        testCart.setCartItemTotal(20.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(2.99);
    }

    @Test
    public void testCalculateShippingForCartBetween25And50() {
        testCart.setCartItemTotal(30.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(4.99);
    }

    @Test
    public void testCalculateShippingForCartBetween50And75() {
        testCart.setCartItemTotal(60.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(6.99);
    }

    @Test
    public void testCalculateShippingForCartBetween75And100() {
        testCart.setCartItemTotal(80.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(8.99);
    }

    @Test
    public void testCalculateShippingForCartOver100() {
        testCart.setCartItemTotal(150.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(10.99);
    }

    @Test
    public void testCalculateShippingAtBoundary25() {
        testCart.setCartItemTotal(25.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(4.99);
    }

    @Test
    public void testCalculateShippingAtBoundary50() {
        testCart.setCartItemTotal(50.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(6.99);
    }

    @Test
    public void testCalculateShippingAtBoundary75() {
        testCart.setCartItemTotal(75.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(8.99);
    }

    @Test
    public void testCalculateShippingAtBoundary100() {
        testCart.setCartItemTotal(100.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(10.99);
    }

    @Test
    public void testCalculateShippingForNullCart() {
        double shipping = shippingService.calculateShipping(null);

        assertThat(shipping).isEqualTo(0);
    }

    @Test
    public void testCalculateShippingForZeroCart() {
        testCart.setCartItemTotal(0.0);

        double shipping = shippingService.calculateShipping(testCart);

        assertThat(shipping).isEqualTo(2.99);
    }

    @Test
    public void testCalculateShippingInsuranceForCartUnder25() {
        testCart.setCartItemTotal(20.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        assertThat(insurance).isEqualTo(0);
    }

    @Test
    public void testCalculateShippingInsuranceForCartBetween25And100() {
        testCart.setCartItemTotal(50.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 2% of 50.0 = 1.0
        assertThat(insurance).isCloseTo(1.0, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceForCartBetween100And500() {
        testCart.setCartItemTotal(200.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 1.5% of 200.0 = 3.0
        assertThat(insurance).isCloseTo(3.0, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceForCartOver500() {
        testCart.setCartItemTotal(1000.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 1% of 1000.0 = 10.0
        assertThat(insurance).isCloseTo(10.0, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceAtBoundary25() {
        testCart.setCartItemTotal(25.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 2% of 25.0 = 0.50
        assertThat(insurance).isCloseTo(0.50, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceAtBoundary100() {
        testCart.setCartItemTotal(100.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 1.5% of 100.0 = 1.50
        assertThat(insurance).isCloseTo(1.50, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceAtBoundary500() {
        testCart.setCartItemTotal(500.0);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 1% of 500.0 = 5.0
        assertThat(insurance).isCloseTo(5.0, within(0.01));
    }

    @Test
    public void testCalculateShippingInsuranceForNullCart() {
        double insurance = shippingService.calculateShippingInsurance(null);

        assertThat(insurance).isEqualTo(0);
    }

    @Test
    public void testCalculateShippingInsuranceRounding() {
        testCart.setCartItemTotal(33.33);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        // 2% of 33.33 = 0.6666, should round to 0.67
        assertThat(insurance).isCloseTo(0.67, within(0.001));
    }

    @Test
    public void testTotalShippingCostWithInsurance() {
        testCart.setCartItemTotal(50.0);

        double shipping = shippingService.calculateShipping(testCart);
        double insurance = shippingService.calculateShippingInsurance(testCart);
        double total = shipping + insurance;

        // Shipping: 6.99, Insurance: 1.0, Total: 7.99
        assertThat(total).isCloseTo(7.99, within(0.01));
    }

    @Test
    public void testShippingInsuranceIsNotAppliedUnderThreshold() {
        testCart.setCartItemTotal(24.99);

        double insurance = shippingService.calculateShippingInsurance(testCart);

        assertThat(insurance).isEqualTo(0);
    }
}
