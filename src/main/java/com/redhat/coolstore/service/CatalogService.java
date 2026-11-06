package com.redhat.coolstore.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.InventoryEntity;

/**
 * Catalogue access. Formerly an EJB {@code @Stateless} bean: the container-provided
 * transaction boundary is now declared explicitly with {@link Transactional}.
 */
@ApplicationScoped
public class CatalogService {

    @Inject
    Logger log;

    @Inject
    EntityManager em;

    public CatalogService() {
    }

    @Transactional
    public List<CatalogItemEntity> getCatalogItems() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CatalogItemEntity> criteria = cb.createQuery(CatalogItemEntity.class);
        Root<CatalogItemEntity> member = criteria.from(CatalogItemEntity.class);
        criteria.select(member);
        return em.createQuery(criteria).getResultList();
    }

    @Transactional
    public CatalogItemEntity getCatalogItemById(String itemId) {
        return em.find(CatalogItemEntity.class, itemId);
    }

    /**
     * Decrements {@code INVENTORY.quantity} for the given catalogue item.
     * Behaviour is unchanged from the EJB implementation: the entity is loaded through the
     * catalogue item and merged back.
     */
    @Transactional
    public void updateInventoryItems(String itemId, int deducts) {
        InventoryEntity inventoryEntity = getCatalogItemById(itemId).getInventory();
        int currentQuantity = inventoryEntity.getQuantity();
        inventoryEntity.setQuantity(currentQuantity - deducts);
        em.merge(inventoryEntity);
    }

}
