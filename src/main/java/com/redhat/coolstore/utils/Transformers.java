package com.redhat.coolstore.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;

/**
 * JSON transformations between the domain model and the order-event wire format.
 *
 * <p>The monolith used JSON-P builders. This port uses Jackson but keeps the
 * contract byte-for-byte compatible: identical key names, identical insertion order and identical
 * number formatting ({@code double} values serialise as {@code 20.0}, as JSON-P did).
 *
 * <p>Wire contract of the order event:
 * <pre>
 * { "orderValue": 0.0, "customerName": "...", "customerEmail": "...", "retailPrice": 0.0,
 *   "discount": 0.0, "shippingFee": 0.0, "shippingDiscount": 0.0,
 *   "items": [ { "productSku": "329299", "quantity": 1 } ] }
 * </pre>
 */
public final class Transformers {

    private static final Logger LOG = Logger.getLogger(Transformers.class);

    static final String[] RANDOM_NAMES = {"Sven Karlsson", "Johan Andersson", "Karl Svensson",
            "Anders Johansson", "Stefan Olson", "Martin Ericsson"};
    static final String[] RANDOM_EMAILS = {"sven@gmail.com", "johan@gmail.com", "karl@gmail.com",
            "anders@gmail.com", "stefan@gmail.com", "martin@gmail.com"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Transformers() {
    }

    public static Product toProduct(CatalogItemEntity entity) {
        Product prod = new Product();
        prod.setItemId(entity.getItemId());
        prod.setName(entity.getName());
        prod.setDesc(entity.getDesc());
        prod.setPrice(entity.getPrice());
        if (entity.getInventory() != null) {
            prod.setLocation(entity.getInventory().getLocation());
            prod.setLink(entity.getInventory().getLink());
            prod.setQuantity(entity.getInventory().getQuantity());
        } else {
            LOG.warnf("Inventory for %s[%s] unknown and missing", entity.getName(), entity.getItemId());
        }
        return prod;
    }

    /**
     * Serialises a cart into the order event, picking a random demo customer name/email.
     */
    public static String shoppingCartToJson(ShoppingCart cart) {
        int index = ThreadLocalRandom.current().nextInt(RANDOM_NAMES.length);
        return shoppingCartToJson(cart, RANDOM_NAMES[index], RANDOM_EMAILS[index]);
    }

    /**
     * Deterministic variant used by tests (and by any caller that already knows the customer).
     */
    public static String shoppingCartToJson(ShoppingCart cart, String customerName, String customerEmail) {
        ArrayNode cartItems = MAPPER.createArrayNode();
        cart.getShoppingCartItemList().forEach(item -> {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("productSku", item.getProduct().getItemId());
            node.put("quantity", item.getQuantity());
            cartItems.add(node);
        });

        ObjectNode root = MAPPER.createObjectNode();
        root.put("orderValue", cart.getCartTotal());
        root.put("customerName", customerName);
        root.put("customerEmail", customerEmail);
        root.put("retailPrice", cart.getShoppingCartItemList().stream()
                .mapToDouble(i -> i.getQuantity() * i.getPrice()).sum());
        root.put("discount", cart.getCartItemPromoSavings());
        root.put("shippingFee", cart.getShippingTotal());
        root.put("shippingDiscount", cart.getShippingPromoSavings());
        root.set("items", cartItems);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize shopping cart", e);
        }
    }

    public static Order jsonToOrder(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed order event: " + e.getOriginalMessage(), e);
        }
        Order order = new Order();
        order.setCustomerName(root.path("customerName").asText(null));
        order.setCustomerEmail(root.path("customerEmail").asText(null));
        order.setOrderValue(root.path("orderValue").asDouble());
        order.setRetailPrice(root.path("retailPrice").asDouble());
        order.setDiscount(root.path("discount").asDouble());
        order.setShippingFee(root.path("shippingFee").asDouble());
        order.setShippingDiscount(root.path("shippingDiscount").asDouble());

        List<OrderItem> items = new ArrayList<>();
        JsonNode jsonItems = root.path("items");
        if (jsonItems.isArray()) {
            for (JsonNode jsonItem : jsonItems) {
                OrderItem oi = new OrderItem();
                oi.setProductId(jsonItem.path("productSku").asText(null));
                oi.setQuantity(jsonItem.path("quantity").asInt());
                items.add(oi);
            }
        }
        order.setItemList(items);
        return order;
    }
}
