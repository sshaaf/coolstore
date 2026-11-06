package com.redhat.coolstore.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Cart use cases. Was an EJB {@code @Stateful} bean holding a per-user
 * {@code ShoppingCart} field; now stateless and delegating all state to
 * {@link CartStore}, keyed by the {@code cartId} path parameter.
 *
 * <p>Pricing, promotion and shipping arithmetic are byte-for-byte the legacy behaviour
 * (see the characterization tests).</p>
 */
@ApplicationScoped
public class ShoppingCartService {

    private static final Logger log = Logger.getLogger(ShoppingCartService.class.getName());

    private final ProductService productService;
    private final PromoService promoService;
    private final ShippingService shippingService;
    private final ShoppingCartOrderProcessor orderProcessor;
    private final CartStore cartStore;

    @Inject
    public ShoppingCartService(ProductService productService,
                               PromoService promoService,
                               ShippingService shippingService,
                               ShoppingCartOrderProcessor orderProcessor,
                               CartStore cartStore) {
        this.productService = productService;
        this.promoService = promoService;
        this.shippingService = shippingService;
        this.orderProcessor = orderProcessor;
        this.cartStore = cartStore;
    }

    public ShoppingCart getShoppingCart(String cartId) {
        return cartStore.get(cartId);
    }

    public ShoppingCart checkOutShoppingCart(String cartId) {
        ShoppingCart cart = getShoppingCart(cartId);
        log.info("Sending  order: ");
        orderProcessor.process(cart);
        cart.resetShoppingCartItemList();
        priceShoppingCart(cart);
        cartStore.save(cartId, cart);
        return cart;
    }

    /**
     * Adds an item and re-prices the cart. The item is rolled back if pricing fails,
     * exactly as the legacy {@code CartEndpoint#add} did.
     */
    public ShoppingCart addItem(String cartId, String itemId, int quantity) {
        ShoppingCart cart = getShoppingCart(cartId);
        Product product = getProduct(itemId);
        if (product == null) {
            throw new IllegalArgumentException("Unknown product: " + itemId);
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
        cartStore.save(cartId, cart);
        return cart;
    }

    /** Legacy {@code DELETE /{cartId}/{itemId}/{quantity}}. */
    public ShoppingCart removeItem(String cartId, String itemId, int quantity) {
        ShoppingCart cart = getShoppingCart(cartId);
        List<ShoppingCartItem> toRemoveList = new ArrayList<>();

        cart.getShoppingCartItemList().stream()
                .filter(sci -> sci.getProduct().getItemId().equals(itemId))
                .forEach(sci -> {
                    if (quantity >= sci.getQuantity()) {
                        toRemoveList.add(sci);
                    } else {
                        sci.setQuantity(sci.getQuantity() - quantity);
                    }
                });

        toRemoveList.forEach(cart::removeShoppingCartItem);
        priceShoppingCart(cart);
        cartStore.save(cartId, cart);
        return cart;
    }

    /** Legacy {@code POST /{cartId}/{tmpId}}: copy one cart into another, then re-price. */
    public ShoppingCart copyCart(String cartId, String tmpId) {
        ShoppingCart cart = getShoppingCart(cartId);
        ShoppingCart tmpCart = getShoppingCart(tmpId);

        if (tmpCart != null) {
            cart.resetShoppingCartItemList();
            cart.setShoppingCartItemList(tmpCart.getShoppingCartItemList());
        }

        priceShoppingCart(cart);
        cart.setShoppingCartItemList(dedupeCartItems(cart.getShoppingCartItemList()));
        cartStore.save(cartId, cart);
        return cart;
    }

    /** Merges lines with the same product id, preserving the legacy insertion semantics. */
    public List<ShoppingCartItem> dedupeCartItems(List<ShoppingCartItem> cartItems) {
        List<ShoppingCartItem> result = new ArrayList<>();
        Map<String, Integer> quantityMap = new LinkedHashMap<>();
        for (ShoppingCartItem sci : cartItems) {
            quantityMap.merge(sci.getProduct().getItemId(), sci.getQuantity(), Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : quantityMap.entrySet()) {
            Product p = getProduct(entry.getKey());
            ShoppingCartItem newItem = new ShoppingCartItem();
            newItem.setQuantity(entry.getValue());
            newItem.setPrice(p.getPrice());
            newItem.setProduct(p);
            result.add(newItem);
        }
        return result;
    }

    public void priceShoppingCart(ShoppingCart sc) {
        if (sc != null) {
            initShoppingCartForPricing(sc);

            if (sc.getShoppingCartItemList() != null && !sc.getShoppingCartItemList().isEmpty()) {

                promoService.applyCartItemPromotions(sc);

                for (ShoppingCartItem sci : sc.getShoppingCartItemList()) {
                    sc.setCartItemPromoSavings(sc.getCartItemPromoSavings() + sci.getPromoSavings() * sci.getQuantity());
                    sc.setCartItemTotal(sc.getCartItemTotal() + sci.getPrice() * sci.getQuantity());
                }

                sc.setShippingTotal(shippingService.calculateShipping(sc));

                if (sc.getCartItemTotal() >= 25) {
                    sc.setShippingTotal(sc.getShippingTotal() + shippingService.calculateShippingInsurance(sc));
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
}
