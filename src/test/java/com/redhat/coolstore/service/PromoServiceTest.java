package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromoServiceTest {

    private final PromoService promoService = new PromoService();

    private static Product product(String id, double price) {
        Product p = new Product();
        p.setItemId(id);
        p.setPrice(price);
        return p;
    }

    private static ShoppingCartItem item(Product product, int quantity) {
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(product.getPrice());
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void promotionSetContainsTheLegacyQuarkusTshirtPromotion() {
        assertThat(promoService.getPromotions())
                .anySatisfy(promo -> {
                    assertThat(promo.getItemId()).isEqualTo("329299");
                    assertThat(promo.getPercentOff()).isEqualTo(0.25);
                });
    }

    @Test
    void twentyFivePercentOffFor329299() {
        ShoppingCart cart = new ShoppingCart();
        ShoppingCartItem tshirt = item(product("329299", 10.00), 2);
        cart.addShoppingCartItem(tshirt);

        promoService.applyCartItemPromotions(cart);

        assertThat(tshirt.getPrice()).isEqualTo(7.50);
        assertThat(tshirt.getPromoSavings()).isEqualTo(-2.50);
    }

    @Test
    void otherProductsAreNotDiscounted() {
        ShoppingCart cart = new ShoppingCart();
        ShoppingCartItem socks = item(product("165613", 4.15), 3);
        cart.addShoppingCartItem(socks);

        promoService.applyCartItemPromotions(cart);

        assertThat(socks.getPrice()).isEqualTo(4.15);
        assertThat(socks.getPromoSavings()).isZero();
    }

    @Test
    void freeShippingAt75OrMore() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(80);
        cart.setShippingTotal(8.99);

        promoService.applyShippingPromotions(cart);

        assertThat(cart.getShippingTotal()).isZero();
        assertThat(cart.getShippingPromoSavings()).isEqualTo(-8.99);
    }

    @Test
    void shippingIsChargedBelow75() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(74.99);
        cart.setShippingTotal(6.99);

        promoService.applyShippingPromotions(cart);

        assertThat(cart.getShippingTotal()).isEqualTo(6.99);
        assertThat(cart.getShippingPromoSavings()).isZero();
    }

    @Test
    void promotionSetIsDefensiveCopy() {
        assertThat(promoService.getPromotions()).isNotSameAs(promoService.getPromotions());
    }
}
