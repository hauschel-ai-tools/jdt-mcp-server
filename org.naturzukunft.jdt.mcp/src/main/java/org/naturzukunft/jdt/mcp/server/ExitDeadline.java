package org.naturzukunft.jdt.mcp.server;

import java.util.concurrent.atomic.AtomicBoolean;

import org.naturzukunft.jdt.mcp.McpLogger;

/**
 * Hard upper bound for JVM shutdown.
 * <p>
 * Once a shutdown has been decided (stdin closed, parent gone, stop requested),
 * the orderly path — application return, OSGi framework stop, workspace save —
 * may hang on a stuck job or a blocked workspace lock. A server nobody can talk
 * to anymore must not survive that: after the deadline the JVM is halted (#80).
 */
public final class ExitDeadline {

    private static final String COMPONENT = "ExitDeadline";
    private static final long DEFAULT_SECONDS = 60;
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    private ExitDeadline() {
    }

    /**
     * Arms the deadline. Subsequent calls are no-ops.
     *
     * @param reason logged with the deadline so the log tells why the server went down
     */
    public static void arm(String reason) {
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }
        long seconds = Long.getLong("jdtmcp.exitDeadlineSeconds", DEFAULT_SECONDS);
        if (seconds <= 0) {
            McpLogger.info(COMPONENT, "Exit deadline disabled (" + reason + ")");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            McpLogger.error(COMPONENT, "Orderly shutdown did not finish within " + seconds
                    + " s (" + reason + ") — halting JVM");
            Runtime.getRuntime().halt(1);
        }, "mcp-exit-deadline");
        thread.setDaemon(true);
        thread.start();

        McpLogger.info(COMPONENT, "Shutdown started (" + reason + "), JVM halts in "
                + seconds + " s at the latest");
    }
}
