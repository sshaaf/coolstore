package com.redhat.coolstore.service;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Per-shopper cart storage.
 *
 * <p>The monolith relied on a {@code @Stateful} EJB instance plus a {@code @SessionScoped} JAX-RS
 * resource, i.e. cart state lived in the JVM under container-managed session affinity. A Quarkus
 * {@code @ApplicationScoped} bean has no per-client instance, so the cart is now an explicit,
 * keyed store instead. {@link InMemoryCartStore} is the default; a Redis-backed implementation can
 * be supplied behind this same interface for multi-replica deployments without touching
 * {@link ShoppingCartService}.
 */
public interface CartStore {

    /**
     * @return the cart for {@code cartId}, creating an empty one when absent or expired
     */
    ShoppingCart get(String cartId);

    /**
     * Stores (or replaces) the cart for {@code cartId}.
     */
    void put(String cartId, ShoppingCart cart);

    /**
     * Removes the cart for {@code cartId}.
     */
    void remove(String cartId);

    /**
     * @return the number of carts currently held
     */
    int size();
}
