package com.redhat.coolstore.audit;

import java.util.concurrent.CompletableFuture;

import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.service.AuditLogger;

/**
 * No-op {@link AuditLogger} used by the {@code dev} and {@code test} profiles so that unit and
 * {@code @QuarkusTest} runs never attempt a TCP connection to an audit collector.
 *
 * <p>Enable/disable is driven by {@code audit.enabled} (see {@code application.properties}); the
 * production profile defaults to the real {@code StreamableAuditLogger}.</p>
 */
public class NoOpAuditLogger implements AuditLogger {

    @Override
    public CompletableFuture<Void> logEventAsync(AuditEvent event) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> logEventAsyncSafe(AuditEvent event) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void close() {
        // nothing to close
    }

    @Override
    public void logEvent(AuditEvent event) {
        // deprecated synchronous path, deliberately a no-op
    }
}
