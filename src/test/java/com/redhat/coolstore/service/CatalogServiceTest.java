package com.redhat.coolstore.service;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private EntityManager em;
    private CatalogService catalogService;
    private CatalogItemEntity item;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        catalogService = new CatalogService(em);

        InventoryEntity inventory = new InventoryEntity();
        inventory.setItemId("329299");
        inventory.setQuantity(736);
        inventory.setLocation("Raleigh");

        item = new CatalogItemEntity();
        item.setItemId("329299");
        item.setName("Quarkus T-shirt");
        item.setPrice(10.00);
        item.setInventory(inventory);
        when(em.find(CatalogItemEntity.class, "329299")).thenReturn(item);
    }

    @Test
    void findsCatalogItemById() {
        assertThat(catalogService.getCatalogItemById("329299").getName()).isEqualTo("Quarkus T-shirt");
    }

    @Test
    void updateInventoryDeductsAndMerges() {
        catalogService.updateInventoryItems("329299", 5);

        assertThat(item.getInventory().getQuantity()).isEqualTo(731);
        verify(em).merge(item.getInventory());
    }

    @Test
    void unknownItemIsRejected() {
        assertThatThrownBy(() -> catalogService.updateInventoryItems("nope", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
