package org.naturzukunft.jdt.mcp.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Embedded HTTP server for MCP protocol using Jetty.
 * Provides SSE (Server-Sent Events) transport for MCP communication.
 */
public class McpHttpServer implements ProgressNotificationSender {

    private final Server server;
    private final int port;
    private final McpProtocolHandler protocolHandler;
    private final Map<String, SseConnection> sseConnections = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);
    private final String sessionId = UUID.randomUUID().toString();

    public McpHttpServer(int port, McpProtocolHandler protocolHandler) {
        this.port = port;
        this.protocolHandler = protocolHandler;
        this.server = new Server();

        // Configure connector
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        // Setup servlet context
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        // SSE endpoint - for establishing SSE connection
        context.addServlet(new ServletHolder(new SseServlet()), "/sse");

        // Message endpoint - for sending messages to the server (SSE mode)
        context.addServlet(new ServletHolder(new MessageServlet()), "/message");

        // MCP HTTP endpoint - direct HTTP transport (like Spring Tools MCP)
        context.addServlet(new ServletHolder(new McpHttpServlet()), "/mcp");

        server.setHandler(context);
    }

    /**
     * Starts the HTTP server.
     */
    public void start() throws Exception {
        server.start();
        System.err.println("[JDT MCP] HTTP Server started on port " + port);
        System.err.println("[JDT MCP] MCP HTTP endpoint: http://localhost:" + port + "/mcp (recommended)");
        System.err.println("[JDT MCP] SSE endpoint: http://localhost:" + port + "/sse");
        System.err.println("[JDT MCP] Message endpoint: http://localhost:" + port + "/message");
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() throws Exception {
        // Close all SSE connections
        for (SseConnection conn : sseConnections.values()) {
            conn.close();
        }
        sseConnections.clear();

        server.stop();
        System.err.println("[JDT MCP] HTTP Server stopped");
    }

    /**
     * Returns true if the server is running.
     */
    public boolean isRunning() {
        return server.isRunning();
    }

    /**
     * Get the port the server is running on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Send an SSE event to a specific connection.
     */
    public void sendEvent(String connectionId, String event, String data) {
        SseConnection conn = sseConnections.get(connectionId);
        if (conn != null) {
            conn.sendEvent(event, data);
        }
    }

    /**
     * Send an SSE event to all connections.
     */
    public void broadcastEvent(String event, String data) {
        for (SseConnection conn : sseConnections.values()) {
            conn.sendEvent(event, data);
        }
    }

    @Override
    public void sendProgress(String progressToken, int current, int total, String message) {
        // Build MCP progress notification JSON
        String progressJson = String.format(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"%s\",\"progress\":%d,\"total\":%d,\"message\":\"%s\"}}",
            progressToken, current, total, message != null ? message.replace("\"", "\\\"") : ""
        );
        // Broadcast to all SSE connections
        broadcastEvent("message", progressJson);
    }

    /**
     * MCP HTTP Servlet - direct HTTP transport (like Spring Tools MCP).
     * Handles JSON-RPC requests synchronously.
     */
    private class McpHttpServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // MCP Session ID header (required by Claude Code)
            resp.setHeader("Mcp-Session-Id", sessionId);

            // CORS headers
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id");
            resp.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");

            // Read request body
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = req.getReader().readLine()) != null) {
                body.append(line);
            }

            String requestJson = body.toString();
            System.err.println("[JDT MCP] HTTP Request: " + requestJson);

            // Process the message
            String responseJson = protocolHandler.handleMessage(requestJson);

            System.err.println("[JDT MCP] HTTP Response: " + responseJson);

            // Return response
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            if (responseJson != null) {
                resp.getWriter().write(responseJson);
            }
        }

        @Override
        protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // MCP Session ID header (required by Claude Code)
            resp.setHeader("Mcp-Session-Id", sessionId);

            // CORS preflight
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id");
            resp.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    /**
     * SSE Servlet - handles SSE connection establishment.
     */
    private class SseServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // Set SSE headers
            resp.setContentType("text/event-stream");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("Connection", "keep-alive");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            // Start async context for long-polling
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0); // No timeout

            // Create connection ID
            String connectionId = "conn-" + connectionIdCounter.incrementAndGet();

            // Create SSE connection
            SseConnection connection = new SseConnection(connectionId, asyncContext);
            sseConnections.put(connectionId, connection);

            System.err.println("[JDT MCP] SSE connection established: " + connectionId);

            // Send endpoint event with message URL (relative path like Spring Tools MCP)
            String messageUrl = "/message?sessionId=" + connectionId;
            connection.sendEvent("endpoint", messageUrl);

            // Handle connection close
            asyncContext.addListener(new jakarta.servlet.AsyncListener() {
                @Override
                public void onComplete(jakarta.servlet.AsyncEvent event) {
                    sseConnections.remove(connectionId);
                    System.err.println("[JDT MCP] SSE connection closed: " + connectionId);
                }

                @Override
                public void onTimeout(jakarta.servlet.AsyncEvent event) {
                    sseConnections.remove(connectionId);
                }

                @Override
                public void onError(jakarta.servlet.AsyncEvent event) {
                    sseConnections.remove(connectionId);
                }

                @Override
                public void onStartAsync(jakarta.servlet.AsyncEvent event) {
                }
            });
        }
    }

    /**
     * Message Servlet - handles incoming MCP messages.
     */
    private class MessageServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // CORS headers
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");

            // Get session ID
            String sessionId = req.getParameter("sessionId");
            if (sessionId == null) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().write("Missing sessionId parameter");
                return;
            }

            // Check if connection exists
            SseConnection connection = sseConnections.get(sessionId);
            if (connection == null) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("Session not found: " + sessionId);
                return;
            }

            // Read request body
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = req.getReader().readLine()) != null) {
                body.append(line);
            }

            String requestJson = body.toString();
            System.err.println("[JDT MCP] Received message: " + requestJson);

            // Process the message
            String responseJson = protocolHandler.handleMessage(requestJson);

            // Send response via SSE
            connection.sendEvent("message", responseJson);

            // Return accepted
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"accepted\"}");
        }

        @Override
        protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // CORS preflight
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    /**
     * Represents an SSE connection.
     */
    private static class SseConnection {
        private final String id;
        private final AsyncContext asyncContext;
        private PrintWriter writer;

        public SseConnection(String id, AsyncContext asyncContext) {
            this.id = id;
            this.asyncContext = asyncContext;
            try {
                this.writer = asyncContext.getResponse().getWriter();
            } catch (IOException e) {
                System.err.println("[JDT MCP] Error getting writer: " + e.getMessage());
            }
        }

        public String getId() {
            return id;
        }

        public synchronized void sendEvent(String event, String data) {
            if (writer != null) {
                try {
                    // Format matching Spring Tools MCP (no spaces after colons)
                    writer.write("id:" + id + "\n");
                    writer.write("event:" + event + "\n");
                    // Handle multi-line data
                    for (String line : data.split("\n")) {
                        writer.write("data:" + line + "\n");
                    }
                    writer.write("\n");
                    writer.flush();
                } catch (Exception e) {
                    System.err.println("[JDT MCP] Error sending SSE event: " + e.getMessage());
                }
            }
        }

        public void close() {
            try {
                asyncContext.complete();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
