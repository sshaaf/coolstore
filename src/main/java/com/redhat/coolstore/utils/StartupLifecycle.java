package com.redhat.coolstore.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

/**
 * Startup/shutdown markers.
 *
 * <p>Replaces {@code utils/StartupListener}, which extended the WebLogic application lifecycle
 * listener. The log messages are kept ({@code AppListener(postStart)} /
 * {@code AppListener(preStop)}) so existing log-based smoke checks keep matching.
 */
@ApplicationScoped
public class StartupLifecycle {

    private static final Logger LOG = Logger.getLogger(StartupLifecycle.class);

    void onStart(@Observes StartupEvent event) {
        LOG.info("AppListener(postStart)");
    }

    void onStop(@Observes ShutdownEvent event) {
        LOG.info("AppListener(preStop)");
    }
}
