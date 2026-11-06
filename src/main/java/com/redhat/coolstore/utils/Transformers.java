package com.redhat.coolstore.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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

import org.jboss.logging.Logger;

/**
 * Model &lt;-&gt; JSON transformation.
 *
 * <p>Rewritten from JSON-P ({@code javax.json}, later {@code jakarta.json}) to Jackson. The wire
 * format is unchanged and pinned by golden-file tests:</p>
 *
 * <pre>
 * {"orderValue":..,"customerName":..,"customerEmail":..,"retailPrice":..,"discount":..,
 *  "shippingFee":..,"shippingDiscount":..,"items":[{"productSku":..,"quantity":..}]}
 * </pre>
 *
 * <p>Field order, key names and the {@code ThreadLocalRandom} customer name/e-mail pick are
 * preserved so that the JSON published on the {@code orders} channel is byte-identical to the
 * document the JBoss EAP deployment put on {@code topic/orders}.</p>
 */
public class Transformers {

    private static final String[] RANDOM_NAMES = {"Sven Karlsson", "Johan Andersson", "Karl Svensson", "Anders Johansson", "Stefan Olson", "Martin Ericsson"};
    private static final String[] RANDOM_EMAILS = {"sven@gmail.com", "johan@gmail.com", "karl@gmail.com", "anders@gmail.com", "stefan@gmail.com", "martin@gmail.com"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger log = Logger.getLogger(Transformers.class);

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
        ArrayNode cartItems = MAPPER.createArrayNode();
        cart.getShoppingCartItemList().forEach(item -> cartItems.addObject()
                .put("productSku", item.getProduct().getItemId())
                .put("quantity", item.getQuantity()));

        int randomNameAndEmailIndex = ThreadLocalRandom.current().nextInt(RANDOM_NAMES.length);

        ObjectNode jsonObject = MAPPER.createObjectNode()
                .put("orderValue", cart.getCartTotal())
                .put("customerName", RANDOM_NAMES[randomNameAndEmailIndex])
                .put("customerEmail", RANDOM_EMAILS[randomNameAndEmailIndex])
                .put("retailPrice", cart.getShoppingCartItemList().stream().mapToDouble(i -> i.getQuantity() * i.getPrice()).sum())
                .put("discount", cart.getCartItemPromoSavings())
                .put("shippingFee", cart.getShippingTotal())
                .put("shippingDiscount", cart.getShippingPromoSavings());
        jsonObject.set("items", cartItems);

        try {
            return MAPPER.writeValueAsString(jsonObject);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static Order jsonToOrder(String json) {
        JsonNode rootObject;
        try {
            rootObject = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // Same contract as the JSON-P implementation: malformed input surfaces as an
            // unchecked exception (JsonParsingException before, RuntimeException now).
            throw new RuntimeException(e);
        }
        Order order = new Order();
        order.setCustomerName(required(rootObject, "customerName").asText());
        order.setCustomerEmail(required(rootObject, "customerEmail").asText());
        order.setOrderValue(required(rootObject, "orderValue").asDouble());
        order.setRetailPrice(required(rootObject, "retailPrice").asDouble());
        order.setDiscount(required(rootObject, "discount").asDouble());
        order.setShippingFee(required(rootObject, "shippingFee").asDouble());
        order.setShippingDiscount(required(rootObject, "shippingDiscount").asDouble());
        JsonNode jsonItems = required(rootObject, "items");
        List<OrderItem> items = new ArrayList<>(jsonItems.size());
        for (JsonNode jsonItem : jsonItems) {
            OrderItem oi = new OrderItem();
            oi.setProductId(required(jsonItem, "productSku").asText());
            oi.setQuantity(required(jsonItem, "quantity").asInt());
            items.add(oi);
        }
        order.setItemList(items);
        return order;
    }

    /** Missing members must fail like JSON-P did (NullPointerException), not silently become 0. */
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new NullPointerException("Missing required JSON field: " + field);
        }
        return value;
    }

}
