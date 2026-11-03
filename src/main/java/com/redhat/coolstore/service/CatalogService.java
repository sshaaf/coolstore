package com.redhat.coolstore.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;

/**
 * Catalog read/write access. Replaces the {@code @Stateless} EJB of the Java EE
 * application; container-managed transactions become {@code @Transactional}.
 */
@ApplicationScoped
public class CatalogService {

    @Inject
    Logger log;

    @Inject
    EntityManager em;

    public CatalogService() {
    }

    public List<CatalogItemEntity> getCatalogItems() {
        return em.createQuery("select c from CatalogItemEntity c", CatalogItemEntity.class).getResultList();
    }

    public CatalogItemEntity getCatalogItemById(String itemId) {
        return em.find(CatalogItemEntity.class, itemId);
    }

    @Transactional
    public void updateInventoryItems(String itemId, int deducts) {
        CatalogItemEntity catalogItem = getCatalogItemById(itemId);
        if (catalogItem == null || catalogItem.getInventory() == null) {
            log.warnf("Cannot update inventory for unknown item %s", itemId);
            return;
        }
        InventoryEntity inventoryEntity = catalogItem.getInventory();
        inventoryEntity.setQuantity(inventoryEntity.getQuantity() - deducts);
        em.merge(inventoryEntity);
    }
}
