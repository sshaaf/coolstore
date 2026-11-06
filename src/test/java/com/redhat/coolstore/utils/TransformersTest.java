package com.redhat.coolstore.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;

/**
 * Wire-contract characterization for {@link Transformers} (Story 1 task 1.5, Story 4 task 4.1).
 *
 * <p>The golden documents below are exactly what the JSON-P implementation produced on JBoss EAP
 * 7.4 — key order included. Customer name/e-mail are randomized from a fixed table, so they are
 * masked before comparison and asserted separately.</p>
 */
class TransformersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> NAMES = Set.of("Sven Karlsson", "Johan Andersson", "Karl Svensson",
            "Anders Johansson", "Stefan Olson", "Martin Ericsson");

    private static ShoppingCartItem item(String sku, double price, int quantity) {
        Product product = new Product();
        product.setItemId(sku);
        product.setPrice(price);
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(price);
        item.setQuantity(quantity);
        return item;
    }

    private static String maskRandomFields(String json) {
        return json
                .replaceAll("\"customerName\":\"[^\"]*\"", "\"customerName\":\"NAME\"")
                .replaceAll("\"customerEmail\":\"[^\"]*\"", "\"customerEmail\":\"EMAIL\"");
    }

    @Test
    @DisplayName("empty cart produces the documented golden document")
    void emptyCartGolden() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of());

        String json = maskRandomFields(Transformers.shoppingCartToJson(cart));

        assertEquals("{\"orderValue\":0.0,\"customerName\":\"NAME\",\"customerEmail\":\"EMAIL\","
                + "\"retailPrice\":0.0,\"discount\":0.0,\"shippingFee\":0.0,\"shippingDiscount\":0.0,"
                + "\"items\":[]}", json);
    }

    @Test
    @DisplayName("single-item cart produces the documented golden document")
    void singleItemCartGolden() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(item("329299", 10.00, 2)));
        cart.setCartTotal(22.99);
        cart.setShippingTotal(2.99);

        String json = maskRandomFields(Transformers.shoppingCartToJson(cart));

        assertEquals("{\"orderValue\":22.99,\"customerName\":\"NAME\",\"customerEmail\":\"EMAIL\","
                + "\"retailPrice\":20.0,\"discount\":0.0,\"shippingFee\":2.99,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"329299\",\"quantity\":2}]}", json);
    }

    @Test
    @DisplayName("multi-item cart with promotions produces the documented golden document")
    void multiItemCartGolden() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(item("329299", 7.50, 3), item("444435", 13.00, 1)));
        cart.setCartItemPromoSavings(-7.50);
        cart.setCartTotal(40.0);
        cart.setShippingTotal(4.99);

        String json = maskRandomFields(Transformers.shoppingCartToJson(cart));

        assertEquals("{\"orderValue\":40.0,\"customerName\":\"NAME\",\"customerEmail\":\"EMAIL\","
                + "\"retailPrice\":35.5,\"discount\":-7.5,\"shippingFee\":4.99,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"329299\",\"quantity\":3},"
                + "{\"productSku\":\"444435\",\"quantity\":1}]}", json);
    }

    @Test
    @DisplayName("customer name and e-mail come from the same random index")
    void randomNameMatchesEmail() throws Exception {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of());

        JsonNode node = MAPPER.readTree(Transformers.shoppingCartToJson(cart));
        String name = node.get("customerName").asText();
        String email = node.get("customerEmail").asText();

        assertTrue(NAMES.contains(name), "unexpected customer name " + name);
        String expectedPrefix = name.substring(0, name.indexOf(' ')).toLowerCase();
        assertTrue(email.startsWith(expectedPrefix), "email " + email + " does not match name " + name);
    }

    @Test
    @DisplayName("jsonToOrder parses the documented checkout document")
    void jsonToOrderParsesContract() {
        String json = "{\"orderValue\":40.0,\"customerName\":\"Sven Karlsson\",\"customerEmail\":\"sven@gmail.com\","
                + "\"retailPrice\":35.5,\"discount\":-7.5,\"shippingFee\":4.99,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"329299\",\"quantity\":3},{\"productSku\":\"444435\",\"quantity\":1}]}";

        Order order = Transformers.jsonToOrder(json);

        assertEquals("Sven Karlsson", order.getCustomerName());
        assertEquals("sven@gmail.com", order.getCustomerEmail());
        assertEquals(40.0, order.getOrderValue(), 0.0001);
        assertEquals(35.5, order.getRetailPrice(), 0.0001);
        assertEquals(-7.5, order.getDiscount(), 0.0001);
        assertEquals(4.99, order.getShippingFee(), 0.0001);
        assertEquals(0.0, order.getShippingDiscount(), 0.0001);
        assertEquals(2, order.getItemList().size());
        assertEquals("329299", order.getItemList().get(0).getProductId());
        assertEquals(3, order.getItemList().get(0).getQuantity());
        assertEquals("444435", order.getItemList().get(1).getProductId());
    }

    @Test
    @DisplayName("round trip shoppingCartToJson -> jsonToOrder keeps the order fields")
    void roundTrip() {
        ShoppingCart cart = new ShoppingCart();
        cart.setShoppingCartItemList(List.of(item("165613", 4.15, 4)));
        cart.setCartItemPromoSavings(0.0);
        cart.setShippingTotal(2.99);
        cart.setShippingPromoSavings(0.0);
        cart.setCartTotal(19.59);

        Order order = Transformers.jsonToOrder(Transformers.shoppingCartToJson(cart));

        assertEquals(19.59, order.getOrderValue(), 0.0001);
        assertEquals(16.6, order.getRetailPrice(), 0.0001);
        assertEquals(2.99, order.getShippingFee(), 0.0001);
        assertEquals(1, order.getItemList().size());
        assertEquals("165613", order.getItemList().get(0).getProductId());
        assertEquals(4, order.getItemList().get(0).getQuantity());
    }

    @Test
    @DisplayName("malformed JSON fails with an unchecked exception, as it did with JSON-P")
    void malformedJson() {
        assertThrows(RuntimeException.class, () -> Transformers.jsonToOrder("{not-json"));
        assertThrows(RuntimeException.class, () -> Transformers.jsonToOrder(""));
    }

    @Test
    @DisplayName("missing mandatory members fail instead of silently defaulting to 0")
    void missingMembers() {
        String withoutName = "{\"orderValue\":1.0,\"customerEmail\":\"a@b.c\",\"retailPrice\":1.0,\"discount\":0.0,"
                + "\"shippingFee\":0.0,\"shippingDiscount\":0.0,\"items\":[]}";
        assertThrows(NullPointerException.class, () -> Transformers.jsonToOrder(withoutName));
    }

    @Test
    @DisplayName("toProduct maps the catalogue entity and its inventory")
    void toProductWithInventory() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("329299");
        entity.setName("Quarkus T-shirt");
        entity.setDesc("desc");
        entity.setPrice(10.00);
        InventoryEntity inventory = new InventoryEntity();
        inventory.setItemId("329299");
        inventory.setLocation("Raleigh");
        inventory.setLink("http://maps.google.com/?q=Raleigh");
        inventory.setQuantity(736);
        entity.setInventory(inventory);

        Product product = Transformers.toProduct(entity);

        assertEquals("329299", product.getItemId());
        assertEquals("Quarkus T-shirt", product.getName());
        assertEquals("desc", product.getDesc());
        assertEquals(10.00, product.getPrice(), 0.0001);
        assertEquals("Raleigh", product.getLocation());
        assertEquals("http://maps.google.com/?q=Raleigh", product.getLink());
        assertEquals(736, product.getQuantity());
    }

    @Test
    @DisplayName("toProduct leaves location/link/quantity unset when inventory is missing")
    void toProductWithoutInventory() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("444437");
        entity.setName("Nanobloc Universal Webcam Cover");
        entity.setPrice(2.75);

        Product product = Transformers.toProduct(entity);

        assertNotNull(product);
        assertNull(product.getLocation());
        assertNull(product.getLink());
        assertEquals(0, product.getQuantity());
    }
}
