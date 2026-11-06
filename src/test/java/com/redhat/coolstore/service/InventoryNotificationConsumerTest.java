package com.redhat.coolstore.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.utils.Transformers;

/**
 * The low-stock rule survived the WebLogic MDB removal: threshold 50, evaluated per order line.
 * A malformed payload must not kill the consumer.
 */
class InventoryNotificationConsumerTest {

    private InventoryNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        CatalogService catalogService = new CatalogService() {
            @Override
            public CatalogItemEntity getCatalogItemById(String itemId) {
                CatalogItemEntity entity = new CatalogItemEntity();
                entity.setItemId(itemId);
                entity.setName("Thing");
                entity.setPrice(1.0);
                InventoryEntity inventory = new InventoryEntity();
                inventory.setItemId(itemId);
                inventory.setQuantity("165614".equals(itemId) ? 51 : 500);
                entity.setInventory(inventory);
                return entity;
            }
        };
        consumer = new InventoryNotificationConsumer(catalogService);
    }

    private static String orderFor(String itemId, int quantity) {
        return "{\"orderValue\":1.0,\"customerName\":\"n\",\"customerEmail\":\"e@x.io\",\"retailPrice\":1.0,"
                + "\"discount\":0.0,\"shippingFee\":0.0,\"shippingDiscount\":0.0,"
                + "\"items\":[{\"productSku\":\"" + itemId + "\",\"quantity\":" + quantity + "}]}";
    }

    @Test
    void belowThresholdIsReportedWithoutFailing() {
        // 51 - 2 = 49 < 50 -> warning is logged
        assertDoesNotThrow(() -> consumer.onOrder(orderFor("165614", 2)));
    }

    @Test
    void aboveThresholdIsSilent() {
        assertDoesNotThrow(() -> consumer.onOrder(orderFor("329299", 1)));
    }

    @Test
    void malformedPayloadIsSwallowed() {
        assertDoesNotThrow(() -> consumer.onOrder("{not json"));
        assertDoesNotThrow(() -> consumer.onOrder(""));
        assertDoesNotThrow(() -> consumer.onOrder(null));
    }

    @Test
    void orderEventWithNoItemsIsTolerated() {
        String json = Transformers.shoppingCartToJson(new com.redhat.coolstore.model.ShoppingCart(), "n", "e@x.io");
        assertDoesNotThrow(() -> consumer.onOrder(json));
    }
}
