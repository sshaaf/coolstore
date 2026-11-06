package com.redhat.coolstore.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.enterprise.context.ApplicationScoped;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Shipping cost and insurance calculation.
 *
 * <p>Replaces the {@code @Stateless @Remote} EJB. The arithmetic is intentionally untouched: the bands
 * and the {@link RoundingMode#HALF_UP} scale-2 insurance rounding are part of the customer-facing
 * contract (see {@code docs/design-notes.md}).
 */
@ApplicationScoped
public class ShippingService {

    public double calculateShipping(ShoppingCart sc) {
        if (sc != null) {
            double total = sc.getCartItemTotal();
            if (total >= 0 && total < 25) {
                return 2.99;
            } else if (total >= 25 && total < 50) {
                return 4.99;
            } else if (total >= 50 && total < 75) {
                return 6.99;
            } else if (total >= 75 && total < 100) {
                return 8.99;
            } else if (total >= 100 && total < 10000) {
                return 10.99;
            }
        }
        return 0;
    }

    public double calculateShippingInsurance(ShoppingCart sc) {
        if (sc != null) {
            double total = sc.getCartItemTotal();
            if (total >= 25 && total < 100) {
                return getPercentOfTotal(total, 0.02);
            } else if (total >= 100 && total < 500) {
                return getPercentOfTotal(total, 0.015);
            } else if (total >= 500 && total < 10000) {
                return getPercentOfTotal(total, 0.01);
            }
        }
        return 0;
    }

    private static double getPercentOfTotal(double value, double percentOfTotal) {
        return BigDecimal.valueOf(value * percentOfTotal)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
