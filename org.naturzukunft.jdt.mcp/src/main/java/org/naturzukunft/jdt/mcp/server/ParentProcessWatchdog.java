package org.naturzukunft.jdt.mcp.server;

import java.util.Optional;

import org.naturzukunft.jdt.mcp.McpLogger;

/**
 * Detects the death of the parent process (the launcher wrapper or the MCP client)
 * and triggers a shutdown callback.
 * <p>
 * In stdio mode the client owns the server's lifetime. Normally the client closes
 * stdin and the reader loop ends. But if the wrapper process dies without closing
 * our stdin (SIGKILL, crash, client stuck in a stopped state), the JVM is reparented
 * and would otherwise live on forever with its full workspace in memory (#80).
 * <p>
 * The check compares the PID of the parent observed at startup with the current
 * parent: a changed or missing parent means we have been reparented (to init, a
 * subreaper or systemd), i.e. the original parent is gone.
 */
public final class ParentProcessWatchdog {

    private static final String COMPONENT = "ParentWatchdog";
    private static final long DEFAULT_INTERVAL_MS = 2000;

    private ParentProcessWatchdog() {
    }

    /**
     * Starts the watchdog as a daemon thread.
     *
     * @param onParentDeath invoked exactly once when the parent process is gone
     * @return true if the watchdog was started, false if the parent PID could not be determined
     */
    public static boolean start(Runnable onParentDeath) {
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        if (parent.isEmpty()) {
            McpLogger.warn(COMPONENT, "Parent process unknown — watchdog not started");
            return false;
        }

        long parentPid = parent.get().pid();
        long intervalMs = Long.getLong("jdtmcp.parentWatchdog.intervalMs", DEFAULT_INTERVAL_MS);

        Thread thread = new Thread(() -> watch(parentPid, intervalMs, onParentDeath), "mcp-parent-watchdog");
        thread.setDaemon(true);
        thread.start();

        McpLogger.info(COMPONENT, "Watching parent process " + parentPid
                + " (interval " + intervalMs + " ms)");
        return true;
    }

    private static void watch(long parentPid, long intervalMs, Runnable onParentDeath) {
        try {
            while (true) {
                Thread.sleep(intervalMs);
                if (!isParentAlive(parentPid)) {
                    McpLogger.warn(COMPONENT, "Parent process " + parentPid
                            + " is gone (current parent: " + currentParentPid() + ") — shutting down");
                    onParentDeath.run();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean isParentAlive(long parentPid) {
        Optional<ProcessHandle> current = ProcessHandle.current().parent();
        return current.isPresent()
                && current.get().pid() == parentPid
                && current.get().isAlive();
    }

    private static String currentParentPid() {
        return ProcessHandle.current().parent()
                .map(p -> Long.toString(p.pid()))
                .orElse("none");
    }
}
