package com.redhat.coolstore.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Default {@link CartStore}: a {@link ConcurrentHashMap} keyed by cart id with idle-time eviction.
 *
 * <p>Suitable for the demo profile and for single-replica deployments. With more than one replica the
 * cart is only consistent when requests are sticky; use the documented Redis variant for real
 * horizontal scaling (see {@code docs/design-notes.md}).
 */
@ApplicationScoped
public class InMemoryCartStore implements CartStore {

    private static final Logger LOG = Logger.getLogger(InMemoryCartStore.class);

    private final Map<String, Entry> carts = new ConcurrentHashMap<>();

    private final long ttlMillis;

    @Inject
    public InMemoryCartStore(@ConfigProperty(name = "cart.store.ttl-seconds", defaultValue = "1800") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
    }

    public InMemoryCartStore() {
        this(1800L);
    }

    @Override
    public ShoppingCart get(String cartId) {
        if (cartId == null) {
            return new ShoppingCart();
        }
        evictExpired();
        Entry entry = carts.compute(cartId, (key, current) -> {
            if (current == null) {
                return new Entry(new ShoppingCart(), System.currentTimeMillis());
            }
            current.lastAccess = System.currentTimeMillis();
            return current;
        });
        return entry.cart;
    }

    @Override
    public void put(String cartId, ShoppingCart cart) {
        if (cartId == null || cart == null) {
            return;
        }
        carts.put(cartId, new Entry(cart, System.currentTimeMillis()));
    }

    @Override
    public void remove(String cartId) {
        if (cartId != null) {
            carts.remove(cartId);
        }
    }

    @Override
    public int size() {
        return carts.size();
    }

    private void evictExpired() {
        if (ttlMillis <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ttlMillis;
        int before = carts.size();
        carts.entrySet().removeIf(e -> e.getValue().lastAccess < cutoff);
        int removed = before - carts.size();
        if (removed > 0) {
            LOG.debugf("Evicted %d idle carts", removed);
        }
    }

    private static final class Entry {
        private final ShoppingCart cart;
        private volatile long lastAccess;

        private Entry(ShoppingCart cart, long lastAccess) {
            this.cart = cart;
            this.lastAccess = lastAccess;
        }
    }
}
