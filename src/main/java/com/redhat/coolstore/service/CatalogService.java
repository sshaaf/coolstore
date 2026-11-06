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
 * Catalogue and inventory access. {@code @Stateless} became {@link ApplicationScoped} plus
 * {@link Transactional} - Quarkus has no EJB container, so the container-managed transaction
 * demarcation has to be declared explicitly.
 */
@ApplicationScoped
public class CatalogService {

    private static final Logger LOG = Logger.getLogger(CatalogService.class);

    @Inject
    EntityManager em;

    public CatalogService() {
    }

    public CatalogService(EntityManager em) {
        this.em = em;
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

    @Transactional
    public void updateInventoryItems(String itemId, int deducts) {
        CatalogItemEntity item = getCatalogItemById(itemId);
        if (item == null) {
            LOG.warnf("Cannot deduct %d units: unknown catalogue item %s", deducts, itemId);
            return;
        }
        InventoryEntity inventoryEntity = item.getInventory();
        int currentQuantity = inventoryEntity.getQuantity();
        inventoryEntity.setQuantity(currentQuantity - deducts);
        em.merge(inventoryEntity);
        LOG.debugf("Inventory for %s: %d -> %d", itemId, currentQuantity, currentQuantity - deducts);
    }
}
