package com.redhat.coolstore.service;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link CartStore} that round-trips through JSON like Redis does. */
public class InMemoryCartStore implements CartStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public ShoppingCart get(String cartId) {
        String payload = store.get(cartId);
        if (payload == null) {
            return new ShoppingCart();
        }
        try {
            return Transformers.mapper().readValue(payload, ShoppingCart.class);
        } catch (Exception e) {
            throw new CartStoreException("decode failed", e);
        }
    }

    @Override
    public void save(String cartId, ShoppingCart cart) {
        try {
            store.put(cartId, Transformers.mapper().writeValueAsString(cart));
        } catch (Exception e) {
            throw new CartStoreException("encode failed", e);
        }
    }

    @Override
    public void reset(String cartId) {
        store.remove(cartId);
    }

    public boolean contains(String cartId) {
        return store.containsKey(cartId);
    }
}
