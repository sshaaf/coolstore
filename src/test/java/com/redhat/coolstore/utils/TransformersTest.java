package com.redhat.coolstore.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransformersTest {

    private static ShoppingCart cart() {
        Product product = new Product();
        product.setItemId("329299");
        product.setPrice(7.50);

        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(7.50);
        item.setQuantity(2);

        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(item);
        cart.setCartItemTotal(15.00);
        cart.setCartItemPromoSavings(-5.00);
        cart.setShippingTotal(2.99);
        cart.setShippingPromoSavings(0);
        cart.setCartTotal(17.99);
        return cart;
    }

    @Test
    void cartJsonKeepsTheLegacyFieldNamesAndOrder() throws Exception {
        String json = Transformers.shoppingCartToJson(cart());

        JsonNode node = Transformers.mapper().readTree(json);
        java.util.List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly(
                "orderValue", "customerName", "customerEmail", "retailPrice",
                "discount", "shippingFee", "shippingDiscount", "items");
        assertThat(node.get("orderValue").asDouble()).isEqualTo(17.99);
        assertThat(node.get("retailPrice").asDouble()).isEqualTo(15.00);
        assertThat(node.get("discount").asDouble()).isEqualTo(-5.00);
        assertThat(node.get("shippingFee").asDouble()).isEqualTo(2.99);
        assertThat(node.get("shippingDiscount").asDouble()).isZero();
        assertThat(node.get("items")).hasSize(1);
        assertThat(node.get("items").get(0).get("productSku").asText()).isEqualTo("329299");
        assertThat(node.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void cartToJsonToOrderRoundTripPreservesMoneyAndItems() {
        Order order = Transformers.jsonToOrder(Transformers.shoppingCartToJson(cart()));

        assertThat(order.getOrderValue()).isEqualTo(17.99);
        assertThat(order.getRetailPrice()).isEqualTo(15.00);
        assertThat(order.getDiscount()).isEqualTo(-5.00);
        assertThat(order.getShippingFee()).isEqualTo(2.99);
        assertThat(order.getShippingDiscount()).isZero();
        assertThat(order.getCustomerName()).isNotBlank();
        assertThat(order.getCustomerEmail()).contains("@");
        assertThat(order.getItemList()).hasSize(1);
        assertThat(order.getItemList().get(0).getProductId()).isEqualTo("329299");
        assertThat(order.getItemList().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void jsonToOrderToleratesUnknownFields() {
        String payload = """
                {"orderValue":10.5,"customerName":"A","customerEmail":"a@b.c","retailPrice":10.5,
                 "discount":0,"shippingFee":2.99,"shippingDiscount":0,
                 "items":[{"productSku":"329299","quantity":1,"extra":"ignored"}],
                 "futureField":"ignored"}
                """;

        Order order = Transformers.jsonToOrder(payload);

        assertThat(order.getItemList()).hasSize(1);
        assertThat(order.getShippingFee()).isEqualTo(2.99);
    }

    @Test
    void toProductFlattensInventory() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("329299");
        entity.setName("Quarkus T-shirt");
        entity.setDesc("");
        entity.setPrice(10.00);
        InventoryEntity inventory = new InventoryEntity();
        inventory.setItemId("329299");
        inventory.setLocation("Raleigh");
        inventory.setQuantity(736);
        inventory.setLink("http://maps.google.com/?q=Raleigh");
        entity.setInventory(inventory);

        Product product = Transformers.toProduct(entity);

        assertThat(product.getItemId()).isEqualTo("329299");
        assertThat(product.getLocation()).isEqualTo("Raleigh");
        assertThat(product.getQuantity()).isEqualTo(736);
        assertThat(product.getPrice()).isEqualTo(10.00);
    }

    @Test
    void toProductWithoutInventoryKeepsNulls() {
        CatalogItemEntity entity = new CatalogItemEntity();
        entity.setItemId("1");
        entity.setName("no inventory");
        entity.setPrice(1.0);

        Product product = Transformers.toProduct(entity);

        assertThat(product.getLocation()).isNull();
        assertThat(product.getQuantity()).isZero();
    }

    @Test
    void emptyCartSerializesToZeroTotals() {
        String json = Transformers.shoppingCartToJson(new ShoppingCart());

        JsonNode node = assertDoesNotThrowParse(json);
        assertThat(node.get("orderValue").asDouble()).isZero();
        assertThat(node.get("items")).isEmpty();
    }

    private static JsonNode assertDoesNotThrowParse(String json) {
        try {
            return Transformers.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void orderSerializationUsesTheSpaFieldNames() throws Exception {
        Order order = new Order();
        order.setOrderValue(12.34);
        order.setCustomerName("A");
        order.setItemList(List.of());

        String json = Transformers.mapper().writeValueAsString(order);

        assertThat(json).contains("\"orderValue\":12.34", "\"customerName\":\"A\"");
    }
}
