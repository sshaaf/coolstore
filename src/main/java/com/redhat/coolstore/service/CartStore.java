package com.redhat.coolstore.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Replacement for the per-session {@code @Stateful} shopping cart bean.
 *
 * <p>The Java EE application kept one {@link ShoppingCart} inside a stateful EJB
 * that was injected into a {@code @SessionScoped} JAX-RS resource. Quarkus has no
 * EJB container and JAX-RS resources are singletons, so carts are held in an
 * explicit, {@code cartId}-keyed store. The HTTP contract is unchanged: the cart
 * id still comes from the client.</p>
 *
 * <p>Scope note: this map is process-local. With more than one replica a client
 * must be routed to the same pod (session affinity) or the store must be backed
 * by a shared cache such as Redis.</p>
 */
@ApplicationScoped
public class CartStore {

    private final Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();

    /** Returns the cart for {@code cartId}, creating an empty one if needed. */
    public ShoppingCart get(String cartId) {
        return carts.computeIfAbsent(cartId, k -> new ShoppingCart());
    }

    /** Returns the cart for {@code cartId} or {@code null} when unknown. */
    public ShoppingCart find(String cartId) {
        return carts.get(cartId);
    }

    public void remove(String cartId) {
        carts.remove(cartId);
    }

    public void clear() {
        carts.clear();
    }

    public int size() {
        return carts.size();
    }
}
