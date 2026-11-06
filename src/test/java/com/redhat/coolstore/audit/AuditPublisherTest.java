package com.redhat.coolstore.audit;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.model.AuditResult;
import com.enterprise.audit.logging.service.StreamableAuditLogger;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPublisherTest {

    private static AuditConfiguration configFor(int port) {
        AuditConfiguration configuration = new AuditConfiguration();
        configuration.setStreamHost("127.0.0.1");
        configuration.setStreamPort(port);
        configuration.setStreamBufferSize(8192);
        configuration.setStreamReconnectMs(500);
        return configuration;
    }

    @Test
    void eventIsStreamedAsOneNdjsonLine() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> received = executor.submit(() -> acceptOneLine(server));

            AuditPublisher publisher = new AuditPublisher(
                    new StreamableAuditLogger(configFor(server.getLocalPort())), true, 500);
            publisher.publish(AuditEventFactory.orderSaved("coolstore", "order-service",
                    null, AuditResult.SUCCESS, "Order persisted")).get(10, TimeUnit.SECONDS);

            String line = received.get(10, TimeUnit.SECONDS);
            assertThat(line).contains("\"event_type\":\"ORDER_PROCESSING\"");
            assertThat(line).contains("\"component\":\"order-service\"");
            assertThat(line).contains(AuditResult.SUCCESS.getValue());
            publisher.close();
            executor.shutdownNow();
        }
    }

    @Test
    void collectorOutageNeverFailsTheCaller() {
        AuditPublisher publisher = new AuditPublisher(
                new StreamableAuditLogger(configFor(1)), true, 100);

        assertThat(publisher.isReady()).isFalse();
        assertThat(publisher.publish(AuditEventFactory.orderEvent("coolstore", "order-service",
                "SAVE", "orders/1", AuditResult.SUCCESS, "msg", null))).isCompleted();
        publisher.close();
    }

    @Test
    void disabledPublisherIsANoOp() {
        AuditPublisher publisher = new AuditPublisher(
                new StreamableAuditLogger(configFor(1)), false, 100);

        assertThat(publisher.isEnabled()).isFalse();
        assertThat(publisher.isReady()).isFalse();
        assertThat(publisher.publish(AuditEventFactory.orderEvent("coolstore", "order-service",
                "SAVE", "orders/1", AuditResult.SUCCESS, "msg", null))).isCompleted();
        publisher.close();
    }

    private static String acceptOneLine(ServerSocket server) throws Exception {
        String line = null;
        // isReady() performs its own TCP probe first, so skip empty connections.
        while (line == null) {
            try (Socket socket = server.accept();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
        }
        return line;
    }
}
