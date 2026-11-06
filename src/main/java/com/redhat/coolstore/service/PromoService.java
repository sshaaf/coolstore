package com.redhat.coolstore.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.redhat.coolstore.model.Promotion;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Promotion engine. The single hard-coded promotion ({@code 329299} at 25% off) and the
 * "free shipping from 75" rule are preserved exactly - the migration is behaviour-preserving.
 */
@ApplicationScoped
public class PromoService {

    private String name = null;

    private Set<Promotion> promotionSet = null;

    public PromoService() {
        promotionSet = new HashSet<>();
        promotionSet.add(new Promotion("329299", .25));
    }

    public void applyCartItemPromotions(ShoppingCart shoppingCart) {
        if (shoppingCart != null && shoppingCart.getShoppingCartItemList().size() > 0) {
            Map<String, Promotion> promoMap = new HashMap<>();
            for (Promotion promo : getPromotions()) {
                promoMap.put(promo.getItemId(), promo);
            }
            for (ShoppingCartItem sci : shoppingCart.getShoppingCartItemList()) {
                String productId = sci.getProduct().getItemId();
                Promotion promo = promoMap.get(productId);
                if (promo != null) {
                    sci.setPromoSavings(sci.getProduct().getPrice() * promo.getPercentOff() * -1);
                    sci.setPrice(sci.getProduct().getPrice() * (1 - promo.getPercentOff()));
                }
            }
        }
    }

    public void applyShippingPromotions(ShoppingCart shoppingCart) {
        if (shoppingCart != null) {
            // PROMO: if cart total is greater than 75, free shipping
            if (shoppingCart.getCartItemTotal() >= 75) {
                shoppingCart.setShippingPromoSavings(shoppingCart.getShippingTotal() * -1);
                shoppingCart.setShippingTotal(0);
            }
        }
    }

    public Set<Promotion> getPromotions() {
        if (promotionSet == null) {
            promotionSet = new HashSet<>();
        }
        return new HashSet<>(promotionSet);
    }

    public void setPromotions(Set<Promotion> promotionSet) {
        if (promotionSet != null) {
            this.promotionSet = new HashSet<>(promotionSet);
        } else {
            this.promotionSet = new HashSet<>();
        }
    }

    @Override
    public String toString() {
        return "PromoService [name=" + name + ", promotionSet=" + promotionSet + "]";
    }
}
