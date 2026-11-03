package com.redhat.coolstore.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.jboss.logging.Logger;

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
 * Entity/DTO transformations and the order wire format.
 *
 * <p>The Java EE version hand-rolled this with JSON-P ({@code javax.json}); JSON
 * serialization is now Jackson based (also required by
 * {@code audit-logging-library} 2.0.0). The produced/consumed document is
 * unchanged: {@code orderValue, customerName, customerEmail, retailPrice,
 * discount, shippingFee, shippingDiscount, items[{productSku, quantity}]}.</p>
 */
public final class Transformers {

    private static final String[] RANDOM_NAMES = {"Sven Karlsson", "Johan Andersson", "Karl Svensson",
            "Anders Johansson", "Stefan Olson", "Martin Ericsson"};
    private static final String[] RANDOM_EMAILS = {"sven@gmail.com", "johan@gmail.com", "karl@gmail.com",
            "anders@gmail.com", "stefan@gmail.com", "martin@gmail.com"};

    private static final Logger log = Logger.getLogger(Transformers.class);

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
            log.warnf("Inventory for %s[%s] unknown and missing", entity.getName(), entity.getItemId());
        }
        return prod;
    }

    public static String shoppingCartToJson(ShoppingCart cart) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode cartItems = root.putArray("items");
        cart.getShoppingCartItemList().forEach(item -> {
            ObjectNode node = cartItems.addObject();
            node.put("productSku", item.getProduct().getItemId());
            node.put("quantity", item.getQuantity());
        });

        int randomNameAndEmailIndex = ThreadLocalRandom.current().nextInt(RANDOM_NAMES.length);
        double retailPrice = cart.getShoppingCartItemList().stream()
                .mapToDouble(i -> i.getQuantity() * i.getPrice()).sum();

        root.put("orderValue", cart.getCartTotal());
        root.put("customerName", RANDOM_NAMES[randomNameAndEmailIndex]);
        root.put("customerEmail", RANDOM_EMAILS[randomNameAndEmailIndex]);
        root.put("retailPrice", retailPrice);
        root.put("discount", cart.getCartItemPromoSavings());
        root.put("shippingFee", cart.getShippingTotal());
        root.put("shippingDiscount", cart.getShippingPromoSavings());
        return root.toString();
    }

    public static Order jsonToOrder(String json) {
        try {
            JsonNode rootObject = MAPPER.readTree(json);
            Order order = new Order();
            order.setCustomerName(rootObject.path("customerName").asText());
            order.setCustomerEmail(rootObject.path("customerEmail").asText());
            order.setOrderValue(rootObject.path("orderValue").asDouble());
            order.setRetailPrice(rootObject.path("retailPrice").asDouble());
            order.setDiscount(rootObject.path("discount").asDouble());
            order.setShippingFee(rootObject.path("shippingFee").asDouble());
            order.setShippingDiscount(rootObject.path("shippingDiscount").asDouble());
            List<OrderItem> items = new ArrayList<>();
            for (JsonNode jsonItem : rootObject.path("items")) {
                OrderItem oi = new OrderItem();
                oi.setProductId(jsonItem.path("productSku").asText());
                oi.setQuantity(jsonItem.path("quantity").asInt());
                items.add(oi);
            }
            order.setItemList(items);
            return order;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize order payload: " + json, e);
        }
    }

}
