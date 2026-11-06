package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Pricing and checkout orchestration.
 *
 * <p>Changes from the EJB version, all behaviour-preserving:</p>
 * <ul>
 *   <li>{@code @Stateful} instance state replaced by the {@link CartStore} lookup keyed by
 *       {@code cartId};</li>
 *   <li>the per-call remote EJB lookup of {@code ShippingService} replaced by direct injection
 *       (two JNDI round-trips removed from <em>every</em> pricing pass);</li>
 *   <li>bean scope {@code @ApplicationScoped} — the class holds no mutable state.</li>
 * </ul>
 */
@ApplicationScoped
public class ShoppingCartService {

    @Inject
    Logger log;

    @Inject
    ProductService productServices;

    @Inject
    PromoService ps;

    @Inject
    ShoppingCartOrderProcessor shoppingCartOrderProcessor;

    @Inject
    ShippingService shippingService;

    @Inject
    CartStore cartStore;

    public ShoppingCartService() {
    }

    public ShoppingCart getShoppingCart(String cartId) {
        ShoppingCart cart = cartStore.get(cartId);
        if (cart == null) {
            cart = cartStore.getOrCreate(cartId);
        }
        return cart;
    }

    public ShoppingCart checkOutShoppingCart(String cartId) {
        ShoppingCart cart = this.getShoppingCart(cartId);

        log.info("Sending  order: ");
        shoppingCartOrderProcessor.process(cart);

        cart.resetShoppingCartItemList();
        priceShoppingCart(cart);
        return cart;
    }

    public void priceShoppingCart(ShoppingCart sc) {

        if (sc != null) {

            initShoppingCartForPricing(sc);

            if (sc.getShoppingCartItemList() != null && sc.getShoppingCartItemList().size() > 0) {

                ps.applyCartItemPromotions(sc);

                for (ShoppingCartItem sci : sc.getShoppingCartItemList()) {

                    sc.setCartItemPromoSavings(
                            sc.getCartItemPromoSavings() + sci.getPromoSavings() * sci.getQuantity());
                    sc.setCartItemTotal(sc.getCartItemTotal() + sci.getPrice() * sci.getQuantity());

                }

                sc.setShippingTotal(shippingService.calculateShipping(sc));

                if (sc.getCartItemTotal() >= 25) {
                    sc.setShippingTotal(sc.getShippingTotal()
                            + shippingService.calculateShippingInsurance(sc));
                }

            }

            ps.applyShippingPromotions(sc);

            sc.setCartTotal(sc.getCartItemTotal() + sc.getShippingTotal());

        }

    }

    private void initShoppingCartForPricing(ShoppingCart sc) {

        sc.setCartItemTotal(0);
        sc.setCartItemPromoSavings(0);
        sc.setShippingTotal(0);
        sc.setShippingPromoSavings(0);
        sc.setCartTotal(0);

        for (ShoppingCartItem sci : sc.getShoppingCartItemList()) {
            Product p = getProduct(sci.getProduct().getItemId());
            //if product exist
            if (p != null) {
                sci.setProduct(p);
                sci.setPrice(p.getPrice());
            }

            sci.setPromoSavings(0);
        }

    }

    public Product getProduct(String itemId) {
        return productServices.getProductByItemId(itemId);
    }

}
