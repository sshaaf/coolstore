package com.redhat.coolstore.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.redhat.coolstore.audit.AuditService;
import com.redhat.coolstore.model.Order;

@ApplicationScoped
public class OrderService {

    @Inject
    EntityManager em;

    @Inject
    AuditService auditService;

    @Transactional
    public void save(Order order) {
        try {
            em.persist(order);
        } catch (RuntimeException e) {
            auditService.orderSaveFailed(order, e);
            throw e;
        }
        auditService.orderSaved(order);
    }

    public List<Order> getOrders() {
        return em.createQuery("select o from Order o", Order.class).getResultList();
    }

    public Order getOrderById(long id) {
        return em.find(Order.class, id);
    }
}
