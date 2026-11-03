package com.redhat.coolstore.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.model.AuditContext;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.enterprise.audit.logging.service.StreamableAuditLogger;
import com.redhat.coolstore.model.Order;

/**
 * Audit trail for order processing on top of {@code audit-logging-library} 2.0.0.
 *
 * <p>Migration notes (Konveyor rules {@code audit-logging-0001…0005}):</p>
 * <ul>
 *   <li>1.0.0's {@code FileSystemAuditLogger} is replaced by {@link StreamableAuditLogger}
 *       streaming events over TCP to a collector.</li>
 *   <li>The deprecated {@code AuditEvent.builder()} is replaced by direct construction of
 *       the Java 21 {@code AuditEvent} record.</li>
 *   <li>Synchronous {@code logEvent} is replaced by non-blocking {@code logEventAsync}.</li>
 *   <li>The audit path never propagates failures to the caller (checkout keeps working if
 *       the collector is down).</li>
 * </ul>
 */
@ApplicationScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);

    @Inject
    @ConfigProperty(name = "audit.stream.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "audit.stream.host", defaultValue = "localhost")
    String streamHost;

    @Inject
    @ConfigProperty(name = "audit.stream.port", defaultValue = "6514")
    int streamPort;

    @Inject
    @ConfigProperty(name = "audit.stream.protocol", defaultValue = "TCP")
    String streamProtocol;

    @Inject
    @ConfigProperty(name = "audit.application", defaultValue = "coolstore-monolith")
    String application;

    @Inject
    @ConfigProperty(name = "audit.component", defaultValue = "order-service")
    String component;

    private volatile StreamableAuditLogger auditLogger;

    @PostConstruct
    void init() {
        if (!enabled) {
            LOG.info("Audit streaming disabled (audit.stream.enabled=false)");
            return;
        }
        try {
            AuditConfiguration config = new AuditConfiguration();
            config.setStreamHost(streamHost);
            config.setStreamPort(streamPort);
            config.setStreamProtocol(streamProtocol);
            config.setAutoCreateDirectory(true);
            this.auditLogger = new StreamableAuditLogger(config);
            LOG.infof("Audit logger streaming to %s:%d (%s)", streamHost, streamPort, streamProtocol);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit logger could not be initialised; audit events will be dropped: %s", e.getMessage());
        }
    }

    public void orderSaved(Order order) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.getOrderId());
        details.put("orderValue", order.getOrderValue());
        details.put("itemCount", order.getItemList() == null ? 0 : order.getItemList().size());
        audit(AuditResult.SUCCESS, "SAVE_ORDER", "orders/" + order.getOrderId(), "Order persisted", details);
    }

    public void orderSaveFailed(Order order, Throwable failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.getOrderId());
        details.put("error", failure.getClass().getName());
        audit(AuditResult.FAILURE, "SAVE_ORDER", "orders/" + order.getOrderId(),
                "Order persistence failed", details);
    }

    void audit(AuditResult result, String action, String resource, String message, Map<String, Object> details) {
        StreamableAuditLogger logger = this.auditLogger;
        if (logger == null) {
            LOG.debugf("Audit event dropped (no logger): action=%s resource=%s result=%s", action, resource, result);
            return;
        }
        AuditEvent event = new AuditEvent(
                Instant.now(),
                "ORDER",
                AuditContext.getUserId(),
                AuditContext.getSessionId(),
                application,
                component,
                action,
                resource,
                result,
                message,
                details,
                AuditContext.getCorrelationId(),
                AuditContext.getSourceIp(),
                AuditContext.getUserAgent());
        try {
            java.util.concurrent.CompletableFuture<?> delivery = logger.logEventAsync(event);
            delivery.whenComplete((ok, failure) -> {
                if (failure != null) {
                    LOG.debugf(failure, "Audit event delivery failed for %s", resource);
                }
            });
        } catch (RuntimeException e) {
            LOG.debugf(e, "Audit event could not be queued for %s", resource);
        }
    }

    @PreDestroy
    void close() {
        StreamableAuditLogger logger = this.auditLogger;
        if (logger != null) {
            try {
                logger.close();
            } catch (RuntimeException e) {
                LOG.debugf(e, "Error while closing the audit logger");
            }
        }
    }
}
