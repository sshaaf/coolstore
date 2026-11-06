package com.redhat.coolstore.service;

import java.util.List;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;

/**
 * Catalog read model and inventory deduction. Was an EJB {@code @Stateless} bean, now a
 * CDI {@code @ApplicationScoped} bean; method contracts are unchanged.
 */
@ApplicationScoped
public class CatalogService {

    @Inject
    Logger log;

    private final EntityManager em;

    @Inject
    public CatalogService(EntityManager em) {
        this.em = em;
    }

    public List<CatalogItemEntity> getCatalogItems() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CatalogItemEntity> criteria = cb.createQuery(CatalogItemEntity.class);
        Root<CatalogItemEntity> member = criteria.from(CatalogItemEntity.class);
        criteria.select(member);
        return em.createQuery(criteria).getResultList();
    }

    public CatalogItemEntity getCatalogItemById(String itemId) {
        return em.find(CatalogItemEntity.class, itemId);
    }

    @Transactional
    public void updateInventoryItems(String itemId, int deducts) {
        CatalogItemEntity item = getCatalogItemById(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Unknown catalog item: " + itemId);
        }
        InventoryEntity inventoryEntity = item.getInventory();
        int currentQuantity = inventoryEntity.getQuantity();
        inventoryEntity.setQuantity(currentQuantity - deducts);
        em.merge(inventoryEntity);
    }
}
