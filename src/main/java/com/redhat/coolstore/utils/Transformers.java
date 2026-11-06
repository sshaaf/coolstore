package com.redhat.coolstore.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * JSON mapping for the order pipeline and the REST layer.
 *
 * <p>Historically JSON-P ({@code javax.json}); rewritten with Jackson while keeping the
 * wire format identical: the field names and their order in
 * {@link #shoppingCartToJson(ShoppingCart)} are part of the {@code topic/orders} message
 * contract and must not change.</p>
 */
public final class Transformers {

    private static final String[] RANDOM_NAMES = {"Sven Karlsson", "Johan Andersson", "Karl Svensson",
            "Anders Johansson", "Stefan Olson", "Martin Ericsson"};
    private static final String[] RANDOM_EMAILS = {"sven@gmail.com", "johan@gmail.com", "karl@gmail.com",
            "anders@gmail.com", "stefan@gmail.com", "martin@gmail.com"};

    private static final Logger log = Logger.getLogger(Transformers.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Transformers() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
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
            log.warning("Inventory for " + entity.getName() + "[" + entity.getItemId() + "] unknown and missing");
        }
        return prod;
    }

    /**
     * Serializes a cart to the {@code topic/orders} message body.
     * Field order and names are frozen by the baseline (task 1.4).
     */
    public static String shoppingCartToJson(ShoppingCart cart) {
        ArrayNode cartItems = MAPPER.createArrayNode();
        cart.getShoppingCartItemList().forEach(item -> {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("productSku", item.getProduct().getItemId());
            node.put("quantity", item.getQuantity());
            cartItems.add(node);
        });

        int randomNameAndEmailIndex = ThreadLocalRandom.current().nextInt(RANDOM_NAMES.length);

        ObjectNode jsonObject = MAPPER.createObjectNode();
        jsonObject.put("orderValue", cart.getCartTotal());
        jsonObject.put("customerName", RANDOM_NAMES[randomNameAndEmailIndex]);
        jsonObject.put("customerEmail", RANDOM_EMAILS[randomNameAndEmailIndex]);
        jsonObject.put("retailPrice",
                cart.getShoppingCartItemList().stream().mapToDouble(i -> i.getQuantity() * i.getPrice()).sum());
        jsonObject.put("discount", cart.getCartItemPromoSavings());
        jsonObject.put("shippingFee", cart.getShippingTotal());
        jsonObject.put("shippingDiscount", cart.getShippingPromoSavings());
        jsonObject.set("items", cartItems);

        try {
            return MAPPER.writeValueAsString(jsonObject);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize shopping cart", e);
        }
    }

    public static Order jsonToOrder(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Order order = new Order();
            order.setCustomerName(root.path("customerName").asText(null));
            order.setCustomerEmail(root.path("customerEmail").asText(null));
            order.setOrderValue(root.path("orderValue").asDouble());
            order.setRetailPrice(root.path("retailPrice").asDouble());
            order.setDiscount(root.path("discount").asDouble());
            order.setShippingFee(root.path("shippingFee").asDouble());
            order.setShippingDiscount(root.path("shippingDiscount").asDouble());
            List<OrderItem> items = new ArrayList<>();
            for (JsonNode jsonItem : root.path("items")) {
                OrderItem oi = new OrderItem();
                oi.setProductId(jsonItem.path("productSku").asText(null));
                oi.setQuantity(jsonItem.path("quantity").asInt());
                items.add(oi);
            }
            order.setItemList(items);
            return order;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse order payload", e);
        }
    }
}
