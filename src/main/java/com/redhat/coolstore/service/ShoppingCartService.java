package com.redhat.coolstore.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.redhat.coolstore.audit.AuditEvents;
import com.redhat.coolstore.audit.AuditService;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Cart state and pricing.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>The {@code @Stateful} EJB held one cart per bean instance; carts are now keyed by
 *       {@code cartId} in a {@link CartStore}, which is the behaviour the REST layer always assumed.</li>
 *   <li>The remote-EJB lookup ({@code ejb:/ROOT/ShippingService!...}) is replaced by direct CDI
 *       injection of {@link ShippingService}.</li>
 *   <li>{@code dedupeCartItems} moved here from {@code CartEndpoint}: it is pricing/business logic,
 *       not HTTP plumbing.</li>
 * </ul>
 */
@ApplicationScoped
public class ShoppingCartService {

    private static final Logger LOG = Logger.getLogger(ShoppingCartService.class);

    @Inject
    ProductService productService;

    @Inject
    PromoService promoService;

    @Inject
    ShoppingCartOrderProcessor orderProcessor;

    @Inject
    ShippingService shippingService;

    @Inject
    CartStore cartStore;

    @Inject
    AuditService auditService;

    public ShoppingCartService() {
    }

    public ShoppingCartService(ProductService productService, PromoService promoService,
                               ShoppingCartOrderProcessor orderProcessor, ShippingService shippingService,
                               CartStore cartStore, AuditService auditService) {
        this.productService = productService;
        this.promoService = promoService;
        this.orderProcessor = orderProcessor;
        this.shippingService = shippingService;
        this.cartStore = cartStore;
        this.auditService = auditService;
    }

    public ShoppingCart getShoppingCart(String cartId) {
        return cartStore.get(cartId);
    }

    /**
     * Adds {@code quantity} of {@code itemId} to the cart, re-prices it and merges duplicate lines.
     * If pricing fails the half-added line is removed again, exactly like the legacy resource did.
     */
    public ShoppingCart addItem(String cartId, String itemId, int quantity) {
        ShoppingCart cart = getShoppingCart(cartId);
        Product product = getProduct(itemId);

        if (product == null) {
            throw new IllegalArgumentException("Unknown itemId: " + itemId);
        }

        ShoppingCartItem sci = new ShoppingCartItem();
        sci.setProduct(product);
        sci.setQuantity(quantity);
        sci.setPrice(product.getPrice());
        cart.addShoppingCartItem(sci);

        try {
            priceShoppingCart(cart);
            cart.setShoppingCartItemList(dedupeCartItems(cart.getShoppingCartItemList()));
        } catch (RuntimeException ex) {
            cart.removeShoppingCartItem(sci);
            throw ex;
        }
        cartStore.put(cartId, cart);
        return cart;
    }

    /**
     * Transfers the contents of the anonymous cart {@code tmpId} into {@code cartId} (the SPA calls this
     * after a user logs in).
     */
    public ShoppingCart setCart(String cartId, String tmpId) {
        ShoppingCart cart = getShoppingCart(cartId);
        ShoppingCart tmpCart = cartStore.get(tmpId);

        if (tmpCart != null) {
            cart.resetShoppingCartItemList();
            // defensive copy: carts must not alias each other's item lists
            cart.setShoppingCartItemList(new ArrayList<>(tmpCart.getShoppingCartItemList()));
        }

        priceShoppingCart(cart);
        cart.setShoppingCartItemList(dedupeCartItems(cart.getShoppingCartItemList()));
        cartStore.put(cartId, cart);
        return cart;
    }

    /**
     * Removes {@code quantity} units of {@code itemId}; a line is dropped when the requested quantity
     * is greater than or equal to the line quantity, otherwise the quantity is decremented.
     */
    public ShoppingCart deleteItem(String cartId, String itemId, int quantity) {
        List<ShoppingCartItem> toRemoveList = new ArrayList<>();
        ShoppingCart cart = getShoppingCart(cartId);

        cart.getShoppingCartItemList().stream()
                .filter(sci -> sci.getProduct() != null && itemId.equals(sci.getProduct().getItemId()))
                .forEach(sci -> {
                    if (quantity >= sci.getQuantity()) {
                        toRemoveList.add(sci);
                    } else {
                        sci.setQuantity(sci.getQuantity() - quantity);
                    }
                });

        toRemoveList.forEach(cart::removeShoppingCartItem);

        priceShoppingCart(cart);
        cartStore.put(cartId, cart);
        return cart;
    }

    /**
     * Publishes the order, then resets the cart and re-prices it - the same publish/reset/reprice order
     * as the JMS implementation.
     */
    public ShoppingCart checkOutShoppingCart(String cartId) {
        ShoppingCart cart = getShoppingCart(cartId);

        LOG.info("Sending order: ");
        try {
            orderProcessor.process(cart);
            auditService.emit(AuditEvents.checkoutSubmitted(cartId, cart));
        } catch (RuntimeException ex) {
            auditService.emit(AuditEvents.checkoutFailed(cartId, ex));
            throw ex;
        }

        cart.resetShoppingCartItemList();
        priceShoppingCart(cart);
        cartStore.put(cartId, cart);
        return cart;
    }

    public void priceShoppingCart(ShoppingCart sc) {
        if (sc != null) {
            initShoppingCartForPricing(sc);

            if (sc.getShoppingCartItemList() != null && sc.getShoppingCartItemList().size() > 0) {
                promoService.applyCartItemPromotions(sc);

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

            promoService.applyShippingPromotions(sc);

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
            if (p != null) {
                sci.setProduct(p);
                sci.setPrice(p.getPrice());
            }
            sci.setPromoSavings(0);
        }
    }

    public Product getProduct(String itemId) {
        return productService.getProductByItemId(itemId);
    }

    /**
     * Merges cart lines that refer to the same product, summing quantities and re-reading the current
     * catalogue price. Order of the resulting lines follows the insertion order of the first
     * occurrence, matching the legacy {@code CartEndpoint.dedupeCartItems} behaviour closely enough
     * that the SPA renders identical carts.
     */
    List<ShoppingCartItem> dedupeCartItems(List<ShoppingCartItem> cartItems) {
        List<ShoppingCartItem> result = new ArrayList<>();
        Map<String, Integer> quantityMap = new HashMap<>();
        for (ShoppingCartItem sci : cartItems) {
            String itemId = sci.getProduct().getItemId();
            if (quantityMap.containsKey(itemId)) {
                quantityMap.put(itemId, quantityMap.get(itemId) + sci.getQuantity());
            } else {
                quantityMap.put(itemId, sci.getQuantity());
            }
        }

        for (String itemId : quantityMap.keySet()) {
            Product p = getProduct(itemId);
            ShoppingCartItem newItem = new ShoppingCartItem();
            newItem.setQuantity(quantityMap.get(itemId));
            newItem.setPrice(p == null ? 0 : p.getPrice());
            newItem.setProduct(p);
            result.add(newItem);
        }
        return result;
    }
}
