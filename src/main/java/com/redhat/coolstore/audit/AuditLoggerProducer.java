package com.redhat.coolstore.audit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.service.AuditLogger;
import com.enterprise.audit.logging.service.StreamableAuditLogger;

/**
 * Provides the single {@link AuditLogger} for the application.
 *
 * <p>audit-logging-library 2.0.0 removed {@code FileSystemAuditLogger}; the only transport is
 * {@link StreamableAuditLogger}, which serialises each {@code AuditEvent} as one NDJSON line and
 * writes it to {@code audit.stream.host}:{@code audit.stream.port} over TCP (virtual threads,
 * asynchronous). All transport settings are bound from configuration — no directory or localhost
 * literal is compiled in.</p>
 *
 * <p>{@code audit.enabled=false} (dev/test) switches to {@link NoOpAuditLogger} so tests never open
 * a socket.</p>
 */
@ApplicationScoped
public class AuditLoggerProducer {

    private static final Logger LOG = Logger.getLogger(AuditLoggerProducer.class);

    @ConfigProperty(name = "audit.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "audit.stream.host", defaultValue = "localhost")
    String streamHost;

    @ConfigProperty(name = "audit.stream.port", defaultValue = "9000")
    int streamPort;

    @ConfigProperty(name = "audit.stream.protocol", defaultValue = "TCP")
    String streamProtocol;

    @ConfigProperty(name = "audit.stream.buffer-size", defaultValue = "8192")
    int streamBufferSize;

    @ConfigProperty(name = "audit.stream.reconnect-ms", defaultValue = "5000")
    int streamReconnectMs;

    private AuditLogger auditLogger;

    @Produces
    @Singleton
    public AuditLogger auditLogger() {
        if (!enabled) {
            LOG.info("Audit logging disabled (audit.enabled=false); using no-op audit logger");
            auditLogger = new NoOpAuditLogger();
            return auditLogger;
        }
        AuditConfiguration config = new AuditConfiguration();
        config.setStreamHost(streamHost);
        config.setStreamPort(streamPort);
        config.setStreamProtocol(streamProtocol);
        config.setStreamBufferSize(streamBufferSize);
        config.setStreamReconnectMs(streamReconnectMs);
        LOG.infof("Audit logging enabled; streaming NDJSON to %s:%d (%s)", streamHost, streamPort, streamProtocol);
        auditLogger = new StreamableAuditLogger(config);
        return auditLogger;
    }

    @PreDestroy
    void close() {
        if (auditLogger != null) {
            auditLogger.close();
        }
    }
}
