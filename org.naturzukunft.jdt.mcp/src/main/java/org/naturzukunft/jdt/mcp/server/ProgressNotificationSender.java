package org.naturzukunft.jdt.mcp.server;

/**
 * Interface for sending MCP progress notifications.
 */
@FunctionalInterface
public interface ProgressNotificationSender {

    /**
     * Sends a progress notification to the client.
     *
     * @param progressToken the token identifying the progress stream
     * @param current current progress value
     * @param total total expected value (or -1 if unknown)
     * @param message human-readable status message
     */
    void sendProgress(String progressToken, int current, int total, String message);
}
