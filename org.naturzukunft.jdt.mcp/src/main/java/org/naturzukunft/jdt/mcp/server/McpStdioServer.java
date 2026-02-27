package org.naturzukunft.jdt.mcp.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import org.naturzukunft.jdt.mcp.McpLogger;

/**
 * MCP server transport over stdio (stdin/stdout).
 * Each message is a JSON-RPC object on a single line (NDJSON).
 * stdout is exclusively for MCP protocol traffic.
 * All logging goes to stderr or file.
 */
public class McpStdioServer implements ProgressNotificationSender {

    private final McpProtocolHandler protocolHandler;
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    private volatile boolean running = false;
    private Thread readerThread;

    public McpStdioServer(McpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }

    /**
     * Starts the stdio server. Reads from stdin in a background thread.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;

        readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        McpLogger.info("StdioServer", "MCP stdio server started");
    }

    /**
     * Stops the stdio server.
     */
    public void stop() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        stoppedLatch.countDown();
        McpLogger.info("StdioServer", "MCP stdio server stopped");
    }

    /**
     * Returns true if the server is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Waits until the stdio server has stopped (stdin closed or stop() called).
     */
    public void awaitStop() throws InterruptedException {
        stoppedLatch.await();
    }

    /**
     * Main read loop: reads JSON-RPC messages from stdin, one per line.
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                McpLogger.debug("StdioServer", "Received: " + line);

                String response = protocolHandler.handleMessage(line);

                if (response != null) {
                    writeLine(response);
                    McpLogger.debug("StdioServer", "Sent: " + response);
                }
            }
        } catch (Exception e) {
            if (running) {
                McpLogger.error("StdioServer", "Error in read loop", e);
            }
        } finally {
            running = false;
            stoppedLatch.countDown();
            McpLogger.info("StdioServer", "stdin closed, shutting down");
        }
    }

    /**
     * Writes a single line to stdout (MCP response). Thread-safe.
     */
    private void writeLine(String json) {
        synchronized (System.out) {
            try {
                OutputStream out = System.out;
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            } catch (Exception e) {
                McpLogger.error("StdioServer", "Error writing to stdout", e);
            }
        }
    }

    @Override
    public void sendProgress(String progressToken, int current, int total, String message) {
        String progressJson = String.format(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"%s\",\"progress\":%d,\"total\":%d,\"message\":\"%s\"}}",
                progressToken, current, total, message != null ? message.replace("\"", "\\\"") : "");
        writeLine(progressJson);
    }
}
