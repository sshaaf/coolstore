package com.redhat.coolstore.service;

import com.redhat.coolstore.model.CatalogItemEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for CatalogService.
 * Tests database operations and business logic for catalog management.
 */
@QuarkusTest
public class CatalogServiceTest {

    @Inject
    CatalogService catalogService;

    @Test
    public void testGetCatalogItems() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        assertThat(items).isNotNull();
        assertThat(items).isNotEmpty();
    }

    @Test
    public void testGetCatalogItemById() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");

        assertThat(item).isNotNull();
        assertThat(item.getItemId()).isEqualTo("329299");
        assertThat(item.getName()).isEqualTo("Quarkus T-shirt");
        assertThat(item.getPrice()).isEqualTo(10.0);
    }

    @Test
    public void testGetCatalogItemByInvalidId() {
        CatalogItemEntity item = catalogService.getCatalogItemById("invalid-id");

        assertThat(item).isNull();
    }

    @Test
    public void testGetCatalogItemByNullId() {
        // Hibernate throws exception for null ID - this is expected behavior
        try {
            CatalogItemEntity item = catalogService.getCatalogItemById(null);
            assertThat(item).isNull();
        } catch (IllegalArgumentException e) {
            // Expected when ID is null
            assertThat(e).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @Transactional
    public void testUpdateInventoryItemsDecreasesQuantity() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");
        int initialQuantity = item.getInventory().getQuantity();

        catalogService.updateInventoryItems("329299", 5);

        CatalogItemEntity updatedItem = catalogService.getCatalogItemById("329299");
        assertThat(updatedItem.getInventory().getQuantity())
            .isEqualTo(initialQuantity - 5);
    }

    @Test
    public void testCatalogItemHasInventory() {
        CatalogItemEntity item = catalogService.getCatalogItemById("329299");

        assertThat(item).isNotNull();
        assertThat(item.getInventory()).isNotNull();
        assertThat(item.getInventory().getQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(item.getInventory().getLocation()).isNotBlank();
    }

    @Test
    public void testGetCatalogItemsReturnsValidEntities() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        for (CatalogItemEntity item : items) {
            assertThat(item.getItemId()).isNotNull();
            assertThat(item.getName()).isNotNull();
            assertThat(item.getPrice()).isGreaterThan(0);
            assertThat(item.getInventory()).isNotNull();
        }
    }

    @Test
    public void testCatalogContainsExpectedItems() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        List<String> itemIds = items.stream()
            .map(CatalogItemEntity::getItemId)
            .toList();

        assertThat(itemIds).contains("329299", "329199", "165613", "165614");
    }

    @Test
    public void testCatalogItemsHavePositivePrices() {
        List<CatalogItemEntity> items = catalogService.getCatalogItems();

        for (CatalogItemEntity item : items) {
            assertThat(item.getPrice()).isPositive();
        }
    }
}
