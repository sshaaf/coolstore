package com.redhat.coolstore.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.ShoppingCart;

/**
 * Cart state store keyed by {@code cartId}.
 *
 * <p>The JBoss EAP application kept the cart in instance state of a {@code @Stateful}
 * {@code ShoppingCartService} and on an HTTP-session-scoped JAX-RS resource. That does not work on
 * Quarkus REST (HTTP-session-scoped resources are not supported) and never worked across the two
 * clustered EAP nodes either: each node held its own copy.</p>
 *
 * <p>This store makes the key explicit — the {@code cartId} the SPA already sends on every call
 * (see {@code app/services/cart.js}) — so pricing and checkout are deterministic per cart and a
 * request re-routed to another replica sees the same key space. Eviction is idle-time based with a
 * hard size ceiling to keep memory bounded. The eviction/size semantics are documented in
 * {@code docs/cart-state.md}; a distributed (Infinispan/Redis) backend can replace this class
 * without touching callers.</p>
 */
@ApplicationScoped
public class CartStore {

    private static final Logger LOG = Logger.getLogger(CartStore.class);

    /** Carts untouched for this long are eligible for eviction. */
    @ConfigProperty(name = "cart.store.idle-timeout", defaultValue = "PT4H")
    Duration idleTimeout;

    /** Hard upper bound on retained carts per replica. */
    @ConfigProperty(name = "cart.store.max-carts", defaultValue = "10000")
    int maxCarts;

    private final Map<String, Entry> carts = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    @PostConstruct
    void init() {
        LOG.infof("Cart store initialised (max-carts=%d, idle-timeout=%s)", maxCarts, idleTimeout);
    }

    /** Returns the cart for {@code cartId}, creating an empty one when absent. */
    public ShoppingCart getOrCreate(String cartId) {
        String key = normalize(cartId);
        evictExpired();
        Entry entry = carts.computeIfAbsent(key, k -> new Entry(new ShoppingCart(), counter.incrementAndGet()));
        entry.touch();
        return entry.cart();
    }

    /** Returns the cart for {@code cartId} or {@code null} when there is none (mirrors the old code). */
    public ShoppingCart get(String cartId) {
        Entry entry = carts.get(normalize(cartId));
        if (entry == null) {
            return null;
        }
        entry.touch();
        return entry.cart();
    }

    public Optional<ShoppingCart> find(String cartId) {
        return Optional.ofNullable(get(cartId));
    }

    public void put(String cartId, ShoppingCart cart) {
        String key = normalize(cartId);
        carts.put(key, new Entry(cart, counter.incrementAndGet()));
        enforceCeiling();
    }

    public void remove(String cartId) {
        carts.remove(normalize(cartId));
    }

    public int size() {
        return carts.size();
    }

    public void clear() {
        carts.clear();
    }

    private static String normalize(String cartId) {
        return cartId == null ? "" : cartId;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        carts.entrySet().removeIf(e -> e.getValue().lastAccess().isBefore(cutoff));
        enforceCeiling();
    }

    private void enforceCeiling() {
        int overflow = carts.size() - maxCarts;
        if (overflow <= 0) {
            return;
        }
        carts.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().lastAccess()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(carts::remove);
    }

    private static final class Entry {
        private final ShoppingCart cart;
        private final long sequence;
        private volatile Instant lastAccess = Instant.now();

        Entry(ShoppingCart cart, long sequence) {
            this.cart = cart;
            this.sequence = sequence;
        }

        ShoppingCart cart() {
            return cart;
        }

        long sequence() {
            return sequence;
        }

        Instant lastAccess() {
            return lastAccess;
        }

        void touch() {
            lastAccess = Instant.now();
        }
    }
}
