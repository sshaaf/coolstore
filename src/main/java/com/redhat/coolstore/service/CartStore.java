package com.redhat.coolstore.service;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Externalized cart state. Cart identity is the {@code cartId} path parameter only - no
 * HTTP session and no per-instance state - so the application can restart and scale.
 */
public interface CartStore {

    /** @return the stored cart, or an empty cart when the key is absent/expired. */
    ShoppingCart get(String cartId);

    void save(String cartId, ShoppingCart cart);

    void reset(String cartId);
}
