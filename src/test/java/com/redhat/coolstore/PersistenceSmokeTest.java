package com.redhat.coolstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import com.redhat.coolstore.service.CatalogService;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Infrastructure smoke test: proves Flyway created the JBoss EAP schema (V1_1 + V1_2) and that the
 * entities map onto it without Hibernate schema generation.
 */
@QuarkusTest
class PersistenceSmokeTest {

    @Inject
    CatalogService catalogService;

    @Test
    void flywayCreatedSchemaAndSeedData() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();
        assertNotNull(items);
        assertEquals(9, items.size(), "V1_2 seeds exactly 9 catalogue items");

        CatalogItemEntity first = items.stream().filter(i -> "329299".equals(i.getItemId())).findFirst().orElseThrow();
        assertEquals("Quarkus T-shirt", first.getName());
        assertEquals(10.00, first.getPrice(), 0.0001);
        InventoryEntity inventory = first.getInventory();
        assertNotNull(inventory, "INVENTORY must be joined by itemId");
        assertEquals("Raleigh", inventory.getLocation());
        assertEquals(736, inventory.getQuantity());
    }
}
