package com.redhat.coolstore.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.audit.AuditEvents;
import com.redhat.coolstore.audit.AuditService;

/**
 * Order persistence plus audit emission.
 *
 * <p>The monolith created a {@code FileSystemAuditLogger} in {@code @PostConstruct} and never emitted an
 * event. The migration upgrades to the 2.0.0 record-based API, streams over TCP through
 * {@link AuditService} and actually emits an {@code AuditEvent} whenever an order is saved.
 */
@ApplicationScoped
public class OrderService {

    @Inject
    EntityManager em;

    @Inject
    AuditService auditService;

    public OrderService() {
    }

    public OrderService(EntityManager em, AuditService auditService) {
        this.em = em;
        this.auditService = auditService;
    }

    @Transactional
    public void save(Order order) {
        em.persist(order);
        auditService.emit(AuditEvents.orderSaved(order));
    }

    @Transactional
    public List<Order> getOrders() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Order> criteria = cb.createQuery(Order.class);
        Root<Order> member = criteria.from(Order.class);
        criteria.select(member);
        return em.createQuery(criteria).getResultList();
    }

    @Transactional
    public Order getOrderById(long id) {
        return em.find(Order.class, id);
    }
}
