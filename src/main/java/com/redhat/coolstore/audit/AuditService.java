package com.redhat.coolstore.audit;

import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.service.StreamableAuditLogger;

/**
 * Audit emission through {@code audit-logging-library} 2.0.0.
 *
 * <p>Differences to the 1.0.0 usage in the monolith:
 * <ul>
 *   <li>{@code FileSystemAuditLogger} (and its {@code ./device-inventory-audit-logs} directory) is gone;
 *       events are streamed over TCP with {@link StreamableAuditLogger}.</li>
 *   <li>{@link AuditEvent} is a Java 21 record - instantiated directly, no builder.</li>
 *   <li>Emission is non-blocking through {@code logEventAsync}; failures are logged, never propagated, so
 *       checkout keeps working when the collector is down.</li>
 * </ul>
 *
 * <p>Configuration keys are the ones {@code AuditConfiguration} itself understands
 * ({@code audit.stream.*}); {@code audit.enabled=false} turns emission off entirely (used by tests).
 */
@ApplicationScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);

    @ConfigProperty(name = "audit.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "audit.stream.host", defaultValue = "localhost")
    String streamHost;

    @ConfigProperty(name = "audit.stream.port", defaultValue = "6514")
    int streamPort;

    @ConfigProperty(name = "audit.stream.protocol", defaultValue = "TCP")
    String streamProtocol;

    @ConfigProperty(name = "audit.stream.buffer.size", defaultValue = "8192")
    int streamBufferSize;

    @ConfigProperty(name = "audit.stream.reconnect.ms", defaultValue = "5000")
    int streamReconnectMs;

    private StreamableAuditLogger auditLogger;

    public AuditService() {
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            LOG.info("Audit emission disabled (audit.enabled=false)");
            return;
        }
        AuditConfiguration configuration = new AuditConfiguration();
        configuration.setStreamHost(streamHost);
        configuration.setStreamPort(streamPort);
        configuration.setStreamProtocol(streamProtocol);
        configuration.setStreamBufferSize(streamBufferSize);
        configuration.setStreamReconnectMs(streamReconnectMs);
        this.auditLogger = new StreamableAuditLogger(configuration);
        LOG.infof("Audit logger streaming to %s:%d (%s)", streamHost, streamPort, streamProtocol);
    }

    /**
     * Sends the event asynchronously. Never throws: audit failures must not break a checkout.
     */
    public void emit(AuditEvent event) {
        if (event == null) {
            return;
        }
        if (auditLogger == null) {
            LOG.debugf("Audit event dropped (audit disabled): %s %s", event.action(), event.resource());
            return;
        }
        try {
            CompletableFuture<Void> future = auditLogger.logEventAsync(event);
            future.exceptionally(throwable -> {
                LOG.errorf(throwable, "Failed to stream audit event %s %s", event.action(), event.resource());
                return null;
            });
        } catch (RuntimeException ex) {
            LOG.errorf(ex, "Failed to submit audit event %s %s", event.action(), event.resource());
        }
    }

    public boolean isReady() {
        return auditLogger != null && auditLogger.isReady();
    }

    @PreDestroy
    void close() {
        if (auditLogger != null) {
            try {
                auditLogger.close();
            } catch (RuntimeException ex) {
                LOG.warnf(ex, "Error while closing the audit logger");
            }
            auditLogger = null;
        }
    }
}
