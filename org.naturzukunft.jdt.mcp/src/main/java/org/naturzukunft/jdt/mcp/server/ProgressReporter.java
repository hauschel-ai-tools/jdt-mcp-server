package org.naturzukunft.jdt.mcp.server;

/**
 * Interface for reporting progress during long-running tool operations.
 * Sends MCP progress notifications to the client.
 */
@FunctionalInterface
public interface ProgressReporter {

    /**
     * Reports progress to the client.
     *
     * @param current current progress value
     * @param total total expected value (or -1 if unknown)
     * @param message human-readable status message
     */
    void report(int current, int total, String message);

    /**
     * A no-op reporter for tools that don't need progress reporting.
     */
    static ProgressReporter NOOP = (current, total, message) -> {};
}
