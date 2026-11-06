package com.redhat.coolstore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.ShoppingCart;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed {@link CartStore}: JSON payload under {@code cart:{cartId}} with a TTL.
 * A missing or expired key degrades to an empty cart (HTTP 200), a Redis outage raises a
 * {@link CartStoreException} which the REST layer maps to a 5xx.
 */
@ApplicationScoped
public class RedisCartStore implements CartStore {

    private static final Logger log = Logger.getLogger(RedisCartStore.class.getName());

    private final ValueCommands<String, String> commands;
    private final KeyCommands<String> keys;
    private final ObjectMapper mapper;

    @ConfigProperty(name = "cart.ttl-seconds", defaultValue = "86400")
    long ttlSeconds;

    @ConfigProperty(name = "cart.key-prefix", defaultValue = "cart:")
    String keyPrefix;

    @Inject
    public RedisCartStore(RedisDataSource redisDataSource, ObjectMapper mapper) {
        this.commands = redisDataSource.value(String.class, String.class);
        this.keys = redisDataSource.key(String.class);
        this.mapper = mapper;
    }

    @Override
    public ShoppingCart get(String cartId) {
        try {
            String payload = commands.get(key(cartId));
            if (payload == null || payload.isEmpty()) {
                return new ShoppingCart();
            }
            return mapper.readValue(payload, ShoppingCart.class);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unable to read cart " + cartId + " from Redis", e);
            throw new CartStoreException("Cart store unavailable for cart " + cartId, e);
        }
    }

    @Override
    public void save(String cartId, ShoppingCart cart) {
        try {
            commands.setex(key(cartId), ttlSeconds, mapper.writeValueAsString(cart));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unable to persist cart " + cartId + " to Redis", e);
            throw new CartStoreException("Cart store unavailable for cart " + cartId, e);
        }
    }

    @Override
    public void reset(String cartId) {
        try {
            keys.del(key(cartId));
        } catch (Exception e) {
            throw new CartStoreException("Cart store unavailable for cart " + cartId, e);
        }
    }

    String key(String cartId) {
        return keyPrefix + cartId;
    }
}
