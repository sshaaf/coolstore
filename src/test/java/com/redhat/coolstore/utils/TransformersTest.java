package com.redhat.coolstore.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Golden-file style tests for the JSON wire contract. The key names, order and number formatting are
 * part of the contract with the SPA and with any external order consumer.
 */
class TransformersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ShoppingCart cart() {
        ShoppingCart cart = new ShoppingCart();
        cart.setCartItemTotal(10.0);
        cart.setCartItemPromoSavings(0.0);
        cart.setShippingTotal(0.0);
        cart.setShippingPromoSavings(0.0);
        cart.setCartTotal(10.0);
        ShoppingCartItem sci = new ShoppingCartItem();
        Product p = new Product();
        p.setItemId("329299");
        p.setPrice(10.0);
        sci.setProduct(p);
        sci.setPrice(10.0);
        sci.setQuantity(1);
        cart.addShoppingCartItem(sci);
        return cart;
    }

    @Test
    @DisplayName("shoppingCartToJson emits the legacy keys in the legacy order")
    void shoppingCartToJsonGolden() throws Exception {
        String json = Transformers.shoppingCartToJson(cart(), "Sven Karlsson", "sven@gmail.com");

        assertEquals("{\"orderValue\":10.0,\"customerName\":\"Sven Karlsson\",\"customerEmail\":\"sven@gmail.com\","
                + "\"retailPrice\":10.0,\"discount\":0.0,\"shippingFee\":0.0,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"329299\",\"quantity\":1}]}", json);

        JsonNode node = MAPPER.readTree(json);
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(Arrays.asList("orderValue", "customerName", "customerEmail", "retailPrice", "discount",
                "shippingFee", "shippingDiscount", "items"), keys);
    }

    @Test
    void emptyCartSerialisesToEmptyItemsArray() throws Exception {
        ShoppingCart empty = new ShoppingCart();
        JsonNode node = MAPPER.readTree(Transformers.shoppingCartToJson(empty, "Johan Andersson", "johan@gmail.com"));
        assertEquals(0.0, node.get("orderValue").asDouble(), 0.0);
        assertTrue(node.get("items").isArray());
        assertEquals(0, node.get("items").size());
    }

    @Test
    void multiItemCartSerialisesAllLines() throws Exception {
        ShoppingCart cart = cart();
        ShoppingCartItem second = new ShoppingCartItem();
        Product p = new Product();
        p.setItemId("329199");
        p.setPrice(9.0);
        second.setProduct(p);
        second.setPrice(9.0);
        second.setQuantity(4);
        cart.addShoppingCartItem(second);

        JsonNode items = MAPPER.readTree(Transformers.shoppingCartToJson(cart, "Karl Svensson", "karl@gmail.com"))
                .get("items");
        assertEquals(2, items.size());
        assertEquals("329199", items.get(1).get("productSku").asText());
        assertEquals(4, items.get(1).get("quantity").asInt());
        // retailPrice = 1*10 + 4*9
        assertEquals(46.0, MAPPER.readTree(Transformers.shoppingCartToJson(cart, "Karl Svensson", "karl@gmail.com"))
                .get("retailPrice").asDouble(), 0.0);
    }

    @Test
    @DisplayName("jsonToOrder round-trips the event payload")
    void jsonToOrderRoundTrip() {
        String json = Transformers.shoppingCartToJson(cart(), "Sven Karlsson", "sven@gmail.com");

        Order order = Transformers.jsonToOrder(json);

        assertEquals("Sven Karlsson", order.getCustomerName());
        assertEquals("sven@gmail.com", order.getCustomerEmail());
        assertEquals(10.0, order.getOrderValue(), 0.0);
        assertEquals(10.0, order.getRetailPrice(), 0.0);
        assertEquals(0.0, order.getDiscount(), 0.0);
        assertEquals(0.0, order.getShippingFee(), 0.0);
        assertEquals(0.0, order.getShippingDiscount(), 0.0);
        assertEquals(1, order.getItemList().size());
        assertEquals("329299", order.getItemList().get(0).getProductId());
        assertEquals(1, order.getItemList().get(0).getQuantity());
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Transformers.jsonToOrder("{not json"));
    }

    @Test
    void randomOverloadStaysWithinTheDemoNameSets() {
        String json = Transformers.shoppingCartToJson(cart());
        assertTrue(Arrays.stream(Transformers.RANDOM_NAMES).anyMatch(json::contains));
        assertTrue(Arrays.stream(Transformers.RANDOM_EMAILS).anyMatch(json::contains));
    }

    @Test
    void toProductCopiesInventoryFields() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("329299");
        entity.setName("Quarkus T-shirt");
        entity.setDesc("");
        entity.setPrice(10.0);
        InventoryEntity inventory = new InventoryEntity();
        inventory.setItemId("329299");
        inventory.setQuantity(736);
        inventory.setLocation("Raleigh");
        inventory.setLink("http://maps.google.com/?q=Raleigh");
        entity.setInventory(inventory);

        Product product = Transformers.toProduct(entity);

        assertEquals("329299", product.getItemId());
        assertEquals(736, product.getQuantity());
        assertEquals("Raleigh", product.getLocation());
        assertEquals("http://maps.google.com/?q=Raleigh", product.getLink());
    }

    @Test
    void toProductToleratesMissingInventory() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("x");
        entity.setName("No stock");
        entity.setPrice(1.0);

        Product product = Transformers.toProduct(entity);

        assertEquals("x", product.getItemId());
        assertEquals(0, product.getQuantity());
    }
}
