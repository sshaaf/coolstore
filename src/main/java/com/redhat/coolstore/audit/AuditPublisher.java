package com.redhat.coolstore.audit;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.service.AuditLogger;
import com.enterprise.audit.logging.service.StreamableAuditLogger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.Config;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Audit logging v2 adapter.
 *
 * <p>Replaces the v1 {@code FileSystemAuditLogger} + local {@code ./device-inventory-audit-logs}
 * directory with {@link StreamableAuditLogger} streaming NDJSON over TCP. Emission is
 * non-blocking ({@code logEventAsync}) and exception-safe: a collector outage must never
 * fail checkout.</p>
 */
@ApplicationScoped
public class AuditPublisher {

    private static final Logger log = Logger.getLogger(AuditPublisher.class.getName());

    public static final String CFG_ENABLED = "audit.stream.enabled";
    public static final String CFG_HOST = "audit.stream.host";
    public static final String CFG_PORT = "audit.stream.port";
    public static final String CFG_BUFFER_SIZE = "audit.stream.buffer-size";
    public static final String CFG_RECONNECT_MS = "audit.stream.reconnect-ms";

    private final AuditLogger auditLogger;
    private final boolean enabled;
    private final long reconnectMs;

    private volatile long nextReadinessProbe;
    private volatile boolean ready;

    @Inject
    public AuditPublisher(Config config) {
        this(buildLogger(config), config.getOptionalValue(CFG_ENABLED, Boolean.class).orElse(false),
                config.getOptionalValue(CFG_RECONNECT_MS, Integer.class).orElse(2000));
    }

    AuditPublisher(AuditLogger auditLogger, boolean enabled, long reconnectMs) {
        this.auditLogger = auditLogger;
        this.enabled = enabled;
        this.reconnectMs = reconnectMs;
    }

    static AuditLogger buildLogger(Config config) {
        AuditConfiguration auditConfiguration = new AuditConfiguration();
        auditConfiguration.setStreamHost(config.getOptionalValue(CFG_HOST, String.class).orElse("localhost"));
        auditConfiguration.setStreamPort(config.getOptionalValue(CFG_PORT, Integer.class).orElse(5044));
        auditConfiguration.setStreamBufferSize(config.getOptionalValue(CFG_BUFFER_SIZE, Integer.class).orElse(8192));
        auditConfiguration.setStreamReconnectMs(config.getOptionalValue(CFG_RECONNECT_MS, Integer.class).orElse(2000));
        return new StreamableAuditLogger(auditConfiguration);
    }

    /**
     * Fire-and-forget publish. Never throws and never blocks the caller.
     */
    public CompletableFuture<Void> publish(AuditEvent event) {
        if (!enabled) {
            log.fine(() -> "Audit stream disabled, event not emitted: " + event.action());
            return CompletableFuture.completedFuture(null);
        }
        if (!isReady()) {
            log.warning("Audit collector not reachable, dropping event " + event.event_type() + "/" + event.action());
            return CompletableFuture.completedFuture(null);
        }
        try {
            return auditLogger.logEventAsync(event).exceptionally(throwable -> {
                log.log(Level.WARNING, "Audit event delivery failed: " + throwable.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Audit event submission failed", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Readiness gate, probed at most once per {@code audit.stream.reconnect-ms}. */
    public boolean isReady() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= nextReadinessProbe) {
            try {
                ready = auditLogger.isReady();
            } catch (RuntimeException e) {
                ready = false;
            }
            nextReadinessProbe = now + reconnectMs;
        }
        return ready;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PreDestroy
    void close() {
        try {
            auditLogger.close();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Error while closing the audit logger", e);
        }
    }
}
