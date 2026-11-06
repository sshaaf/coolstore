package com.redhat.coolstore.service;

import com.enterprise.audit.logging.model.AuditResult;
import com.redhat.coolstore.audit.AuditEventFactory;
import com.redhat.coolstore.audit.AuditPublisher;
import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.OrderItem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Order persistence plus audit emission. Was an EJB {@code @Stateless} bean with
 * container-managed transactions; transaction boundaries are now explicit.
 */
@ApplicationScoped
public class OrderService {

    private final EntityManager em;
    private final CatalogService catalogService;
    private final AuditPublisher auditPublisher;

    @ConfigProperty(name = "audit.application", defaultValue = "coolstore")
    String auditApplication;

    @ConfigProperty(name = "audit.component", defaultValue = "order-service")
    String auditComponent;

    @Inject
    public OrderService(EntityManager em, CatalogService catalogService, AuditPublisher auditPublisher) {
        this.em = em;
        this.catalogService = catalogService;
        this.auditPublisher = auditPublisher;
    }

    @Transactional
    public void save(Order order) {
        try {
            em.persist(order);
            auditPublisher.publish(AuditEventFactory.orderSaved(auditApplication, auditComponent, order,
                    AuditResult.SUCCESS, "Order persisted"));
        } catch (RuntimeException e) {
            auditPublisher.publish(AuditEventFactory.orderSaved(auditApplication, auditComponent, order,
                    AuditResult.FAILURE, "Order persist failed: " + e.getMessage()));
            throw e;
        }
    }

    /**
     * Order pipeline step: persist the order then deduct inventory, in one transaction.
     * The broker message is acknowledged only after this method returns (see
     * {@link OrderMessageConsumer}).
     */
    @Transactional
    public Order processOrder(Order order) {
        save(order);
        if (order.getItemList() != null) {
            for (OrderItem item : order.getItemList()) {
                catalogService.updateInventoryItems(item.getProductId(), item.getQuantity());
            }
        }
        return order;
    }

    public List<Order> getOrders() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Order> criteria = cb.createQuery(Order.class);
        Root<Order> member = criteria.from(Order.class);
        criteria.select(member);
        return em.createQuery(criteria).getResultList();
    }

    public Order getOrderById(long id) {
        return em.find(Order.class, id);
    }
}
