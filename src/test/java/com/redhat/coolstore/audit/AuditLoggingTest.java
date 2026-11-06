package com.redhat.coolstore.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.model.AuditResult;
import com.enterprise.audit.logging.service.AuditLogger;
import com.enterprise.audit.logging.service.StreamableAuditLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * audit-logging-library 2.0.0 contract (Story 4 tasks 4.4/4.5/4.6):
 * {@code AuditEvent} as a 14-component record, NDJSON over TCP, async emission, and the failure
 * semantics of a closed logger / unreachable collector.
 */
class AuditLoggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AuditEvent sampleEvent() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderValue", 22.99);
        details.put("itemCount", 1);
        return new AuditEvent(
                Instant.parse("2026-01-01T10:15:30Z"),
                "ORDER_CREATED",
                "user-1",
                "session-1",
                "coolstore",
                "OrderService",
                "save",
                "order:42",
                AuditResult.SUCCESS,
                "Order created",
                details,
                "correlation-1",
                "10.0.0.1",
                "JUnit");
    }

    private static AuditConfiguration config(String host, int port) {
        AuditConfiguration config = new AuditConfiguration();
        config.setStreamHost(host);
        config.setStreamPort(port);
        config.setStreamProtocol("TCP");
        config.setStreamBufferSize(8192);
        config.setStreamReconnectMs(1000);
        return config;
    }

    @Test
    @DisplayName("AuditEvent is the 14-component record with the documented accessors")
    void auditEventRecordShape() {
        AuditEvent event = sampleEvent();

        assertEquals(Instant.parse("2026-01-01T10:15:30Z"), event.timestamp());
        assertEquals("ORDER_CREATED", event.event_type());
        assertEquals("user-1", event.user_id());
        assertEquals("session-1", event.session_id());
        assertEquals("coolstore", event.application());
        assertEquals("OrderService", event.component());
        assertEquals("save", event.action());
        assertEquals("order:42", event.resource());
        assertEquals(AuditResult.SUCCESS, event.result());
        assertEquals("Order created", event.message());
        assertEquals(2, event.details().size());
        assertEquals("correlation-1", event.correlation_id());
        assertEquals("10.0.0.1", event.source_ip());
        assertEquals("JUnit", event.user_agent());
        assertEquals(14, AuditEvent.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("logEventAsync writes exactly one NDJSON line with all 14 fields")
    void streamsNdjsonOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.readLine();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            AuditLogger logger = new StreamableAuditLogger(config("127.0.0.1", server.getLocalPort()));
            CompletableFuture<Void> sent = logger.logEventAsync(sampleEvent());
            sent.get(10, TimeUnit.SECONDS);

            String json = line.get(10, TimeUnit.SECONDS);
            logger.close();

            assertNotNull(json, "the collector must receive one NDJSON line");
            assertFalse(json.contains("\n"));

            JsonNode node = MAPPER.readTree(json);
            assertEquals(14, node.size(), "all 14 record components must be serialized: " + json);
            assertEquals("ORDER_CREATED", node.get("event_type").asText());
            assertEquals("save", node.get("action").asText());
            assertEquals("order:42", node.get("resource").asText());
            assertEquals("success", node.get("result").asText());
            assertEquals("coolstore", node.get("application").asText());
            assertEquals(22.99, node.get("details").get("orderValue").asDouble(), 0.0001);
            // The library serialises Instant as epoch seconds (plain ObjectMapper, no WRITE_DATES_AS_TIMESTAMPS=false).
            assertTrue(node.get("timestamp").isNumber(), "timestamp is epoch seconds: " + json);
            assertEquals(Instant.parse("2026-01-01T10:15:30Z").getEpochSecond(), node.get("timestamp").asLong());
        }
    }

    @Test
    @DisplayName("an unreachable collector makes logEventAsync fail (and only the future)")
    void unreachableCollectorFailsTheFuture() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        AuditLogger logger = new StreamableAuditLogger(config("127.0.0.1", closedPort));
        CompletableFuture<Void> future = logger.logEventAsync(sampleEvent());

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertNotNull(failure.getCause());
        logger.close();
    }

    @Test
    @DisplayName("a closed logger rejects new events (synchronously), which OrderService must survive")
    void closedLoggerRejectsEvents() {
        AuditLogger logger = new StreamableAuditLogger(config("127.0.0.1", 9));
        logger.close();

        // StreamableAuditLogger shuts its executor down on close(), so logEventAsync throws
        // RejectedExecutionException at the call site. OrderService.emitOrderCreated catches
        // RuntimeException for exactly this reason.
        assertThrows(RejectedExecutionException.class, () -> logger.logEventAsync(sampleEvent()));
    }

    @Test
    @DisplayName("the no-op logger used in dev/test never opens a socket")
    void noOpLoggerIsInert() throws Exception {
        AuditLogger logger = new NoOpAuditLogger();

        assertTrue(logger.isReady());
        logger.logEventAsync(sampleEvent()).get(1, TimeUnit.SECONDS);
        logger.logEvent(sampleEvent());
        logger.close();
    }

    @Test
    @DisplayName("AuditConfiguration exposes the audit.stream.* property keys")
    void configurationPropertyKeys() {
        assertEquals("audit.stream.host", AuditConfiguration.STREAM_HOST_KEY);
        assertEquals("audit.stream.port", AuditConfiguration.STREAM_PORT_KEY);

        AuditConfiguration config = new AuditConfiguration();
        config.setStreamHost("collector.example.com");
        config.setStreamPort(5140);

        assertEquals("collector.example.com", config.getStreamHost());
        assertEquals(5140, config.getStreamPort());
        assertTrue(Duration.ofSeconds(1).toMillis() > 0);
    }
}
