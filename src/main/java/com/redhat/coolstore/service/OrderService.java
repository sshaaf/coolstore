package com.redhat.coolstore.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.enterprise.audit.logging.service.AuditLogger;
import com.redhat.coolstore.model.Order;

/**
 * Order persistence plus the audit trail.
 *
 * <p>Audit changes versus the JBoss EAP version:</p>
 * <ul>
 *   <li>{@code FileSystemAuditLogger} (removed in 2.0.0) replaced by {@link AuditLogger} backed by
 *       {@code StreamableAuditLogger};</li>
 *   <li>{@code AuditEvent} is the 14-component record — {@code AuditEvent.builder()},
 *       {@code logSuccess} and {@code logFailure} no longer exist and are not used;</li>
 *   <li>{@code logEventAsync} (not the deprecated {@code logEvent}) with an
 *       {@code exceptionally} handler: a missing/failing collector can never break a checkout.
 *       The previous code constructed a logger but never emitted anything, so this is the agreed
 *       new behaviour (audit-logging-0001 … 0005).</li>
 * </ul>
 */
@ApplicationScoped
public class OrderService {

    @Inject
    EntityManager em;

    @Inject
    AuditLogger auditLogger;

    @Inject
    Logger log;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "coolstore")
    String applicationName;

    @Transactional
    public void save(Order order) {
        em.persist(order);
        emitOrderCreated(order);
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

    private void emitOrderCreated(Order order) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("orderValue", order.getOrderValue());
            details.put("itemCount", order.getItemList() == null ? 0 : order.getItemList().size());
            AuditEvent event = new AuditEvent(
                    Instant.now(),
                    "ORDER_CREATED",
                    null,
                    null,
                    applicationName,
                    "OrderService",
                    "save",
                    "order:" + order.getOrderId(),
                    AuditResult.SUCCESS,
                    "Order created",
                    details,
                    null,
                    null,
                    null);
            auditLogger.logEventAsync(event)
                    .exceptionally(throwable -> {
                        log.warnf(throwable, "Audit event for order %d could not be delivered",
                                order.getOrderId());
                        return null;
                    });
        } catch (RuntimeException e) {
            // A broken audit transport must never fail the business transaction.
            log.warnf(e, "Audit event for order %d could not be submitted", order.getOrderId());
        }
    }

}
