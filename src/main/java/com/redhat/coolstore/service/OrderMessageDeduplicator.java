package com.redhat.coolstore.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Bounded, hash-based duplicate-delivery guard for the {@code orders} consumer.
 *
 * <p>The JBoss EAP message-driven bean double-decremented inventory when the topic redelivered a
 * message. At-least-once delivery is preserved (nothing is acknowledged speculatively), but a
 * payload that has already been processed by this replica is ignored.</p>
 *
 * <p><strong>Limitation (accepted risk, see {@code docs/services.md}):</strong> the guard is
 * per-replica and in-memory, so a redelivery that lands on a different replica after a restart is
 * not caught. Closing that gap needs a durable message-id store (database or Kafka compaction).</p>
 */
@ApplicationScoped
public class OrderMessageDeduplicator {

    @ConfigProperty(name = "orders.dedupe.max-entries", defaultValue = "10000")
    int maxEntries;

    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > maxEntries;
        }
    };

    /**
     * Claims a payload for processing.
     *
     * @return {@code true} the first time a payload is seen, {@code false} for a duplicate
     */
    public synchronized boolean claim(String payload) {
        String digest = digest(payload);
        if (seen.containsKey(digest)) {
            return false;
        }
        seen.put(digest, Boolean.TRUE);
        return true;
    }

    public synchronized int size() {
        return seen.size();
    }

    public synchronized void clear() {
        seen.clear();
    }

    private static String digest(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
