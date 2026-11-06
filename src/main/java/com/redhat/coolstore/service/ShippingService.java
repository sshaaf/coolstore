package com.redhat.coolstore.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.enterprise.context.ApplicationScoped;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Tiered shipping and insurance calculation.
 *
 * <p>Formerly an EJB {@code @Stateless @Remote} bean. The remote interface
 * ({@code ShippingServiceRemote}) and the {@code ejb:/ROOT/ShippingService!...} JNDI lookup are
 * gone: this bean is injected directly into {@link ShoppingCartService}. The pricing tables are
 * unchanged and pinned by characterization tests:</p>
 *
 * <pre>
 * shipping:  0-&lt;25 -&gt; 2.99, 25-&lt;50 -&gt; 4.99, 50-&lt;75 -&gt; 6.99, 75-&lt;100 -&gt; 8.99, 100-&lt;10000 -&gt; 10.99
 * insurance: 25-&lt;100 -&gt; 2 %, 100-&lt;500 -&gt; 1.5 %, 500-&lt;10000 -&gt; 1 % of the cart item total,
 *            rounded HALF_UP to 2 decimals
 * </pre>
 */
@ApplicationScoped
public class ShippingService {

    public double calculateShipping(ShoppingCart sc) {

        if (sc != null) {

            if (sc.getCartItemTotal() >= 0 && sc.getCartItemTotal() < 25) {

                return 2.99;

            } else if (sc.getCartItemTotal() >= 25 && sc.getCartItemTotal() < 50) {

                return 4.99;

            } else if (sc.getCartItemTotal() >= 50 && sc.getCartItemTotal() < 75) {

                return 6.99;

            } else if (sc.getCartItemTotal() >= 75 && sc.getCartItemTotal() < 100) {

                return 8.99;

            } else if (sc.getCartItemTotal() >= 100 && sc.getCartItemTotal() < 10000) {

                return 10.99;

            }

        }

        return 0;

    }

    public double calculateShippingInsurance(ShoppingCart sc) {

        if (sc != null) {

            if (sc.getCartItemTotal() >= 25 && sc.getCartItemTotal() < 100) {

                return getPercentOfTotal(sc.getCartItemTotal(), 0.02);

            } else if (sc.getCartItemTotal() >= 100 && sc.getCartItemTotal() < 500) {

                return getPercentOfTotal(sc.getCartItemTotal(), 0.015);

            } else if (sc.getCartItemTotal() >= 500 && sc.getCartItemTotal() < 10000) {

                return getPercentOfTotal(sc.getCartItemTotal(), 0.01);

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
