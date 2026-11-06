package com.redhat.coolstore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCartStoreTest {

    private ValueCommands<String, String> values;
    private KeyCommands<String> keys;
    private RedisCartStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedisDataSource redisDataSource = mock(RedisDataSource.class);
        values = mock(ValueCommands.class);
        keys = mock(KeyCommands.class);
        when(redisDataSource.value(String.class, String.class)).thenReturn(values);
        when(redisDataSource.key(String.class)).thenReturn(keys);

        store = new RedisCartStore(redisDataSource, new ObjectMapper());
        store.ttlSeconds = 3600;
        store.keyPrefix = "cart:";
    }

    private static ShoppingCart cartWithItem() {
        Product product = new Product();
        product.setItemId("329299");
        product.setPrice(7.50);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(7.50);
        item.setQuantity(2);
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item);
        cart.setCartTotal(17.99);
        return cart;
    }

    @Test
    void missingCartDegradesToAnEmptyCart() {
        when(values.get("cart:abc")).thenReturn(null);

        ShoppingCart cart = store.get("abc");

        assertThat(cart.getShoppingCartItemList()).isEmpty();
        assertThat(cart.getCartTotal()).isZero();
    }

    @Test
    void saveWritesNamespacedKeyWithTtl() throws Exception {
        ShoppingCart cart = cartWithItem();

        store.save("abc", cart);

        verify(values).setex(eq("cart:abc"), eq(3600L), anyString());
    }

    @Test
    void getRoundTripsTheStoredPayload() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(cartWithItem());
        when(values.get("cart:abc")).thenReturn(payload);

        ShoppingCart cart = store.get("abc");

        assertThat(cart.getShoppingCartItemList()).hasSize(1);
        assertThat(cart.getShoppingCartItemList().get(0).getQuantity()).isEqualTo(2);
        assertThat(cart.getCartTotal()).isEqualTo(17.99);
    }

    @Test
    void resetDeletesTheKey() {
        store.reset("abc");

        verify(keys).del("cart:abc");
    }

    @Test
    void redisOutageIsSurfacedAsCartStoreException() {
        when(values.get("cart:abc")).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> store.get("abc"))
                .isInstanceOf(CartStoreException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void corruptPayloadIsSurfacedAsCartStoreException() {
        when(values.get("cart:abc")).thenReturn("not-json");

        assertThatThrownBy(() -> store.get("abc")).isInstanceOf(CartStoreException.class);
    }

    @Test
    void saveFailureIsSurfacedAsCartStoreException() {
        doThrow(new RuntimeException("down")).when(values).setex(anyString(), eq(3600L), anyString());

        assertThatThrownBy(() -> store.save("abc", cartWithItem())).isInstanceOf(CartStoreException.class);
    }
}
