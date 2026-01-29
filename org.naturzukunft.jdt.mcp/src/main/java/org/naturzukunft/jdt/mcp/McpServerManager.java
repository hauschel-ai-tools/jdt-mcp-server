package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.naturzukunft.jdt.mcp.preferences.McpPreferenceConstants;
import org.naturzukunft.jdt.mcp.tools.CodeAnalysisTools;
import org.naturzukunft.jdt.mcp.tools.NavigationTools;
import org.naturzukunft.jdt.mcp.tools.ProjectInfoTools;
import org.naturzukunft.jdt.mcp.tools.RefactoringTools;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Manages the lifecycle of the embedded MCP server.
 */
public class McpServerManager {

    private McpSyncServer mcpServer;
    private HttpServletSseServerTransportProvider transportProvider;
    private int port;
    private boolean running = false;

    /**
     * Starts the MCP server on an available port.
     */
    public void start() {
        if (running) {
            System.out.println("[JDT MCP] Server already running on port " + port);
            return;
        }

        try {
            // Get port range from preferences
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            int portStart = store.getInt(McpPreferenceConstants.PREF_PORT_START);
            int portEnd = store.getInt(McpPreferenceConstants.PREF_PORT_END);

            // Find available port
            port = findAvailablePort(portStart, portEnd);

            // Create transport provider
            transportProvider = HttpServletSseServerTransportProvider.builder()
                    .port(port)
                    .build();

            // Build MCP server with tools
            McpServer.SyncSpec serverSpec = McpServer.sync(transportProvider)
                    .serverInfo(new McpSchema.Implementation(
                            "eclipse-jdt-mcp-server",
                            "1.0.0"))
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(new McpSchema.ServerCapabilities.ToolCapabilities(true))
                            .build());

            // Register Code Analysis Tools
            registerTool(serverSpec, CodeAnalysisTools.parseJavaFileTool());
            registerTool(serverSpec, CodeAnalysisTools.getTypeHierarchyTool());
            registerTool(serverSpec, CodeAnalysisTools.findReferencesTool());

            // Register Navigation Tools
            registerTool(serverSpec, NavigationTools.findTypeTool());
            registerTool(serverSpec, NavigationTools.getMethodSignatureTool());

            // Register Project Info Tools
            registerTool(serverSpec, ProjectInfoTools.getClasspathTool());
            registerTool(serverSpec, ProjectInfoTools.getCompilationErrorsTool());
            registerTool(serverSpec, ProjectInfoTools.getProjectStructureTool());
            registerTool(serverSpec, ProjectInfoTools.listProjectsTool());

            // Register Refactoring Tools
            registerTool(serverSpec, RefactoringTools.renameElementTool());
            registerTool(serverSpec, RefactoringTools.extractMethodTool());

            // Build and start
            mcpServer = serverSpec.build();
            transportProvider.start();

            running = true;
            System.out.println("[JDT MCP] Server started on port " + port);
            System.out.println("[JDT MCP] Endpoint: http://localhost:" + port + "/sse");

        } catch (Exception e) {
            System.err.println("[JDT MCP] Failed to start server: " + e.getMessage());
            e.printStackTrace();
            running = false;
        }
    }

    /**
     * Registers a tool with the MCP server.
     */
    private void registerTool(McpServer.SyncSpec serverSpec, ToolRegistration registration) {
        serverSpec.tool(
                registration.tool(),
                (exchange, args) -> registration.handler().handle(args));
    }

    /**
     * Stops the MCP server.
     */
    public void stop() {
        if (!running) {
            return;
        }

        try {
            if (transportProvider != null) {
                transportProvider.close();
            }
            if (mcpServer != null) {
                mcpServer.close();
            }
            running = false;
            System.out.println("[JDT MCP] Server stopped");
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error stopping server: " + e.getMessage());
        }
    }

    /**
     * Returns true if the server is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the port the server is running on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Finds an available port in the given range.
     */
    private int findAvailablePort(int startPort, int endPort) throws IOException {
        for (int p = startPort; p <= endPort; p++) {
            try (ServerSocket socket = new ServerSocket(p)) {
                return p;
            } catch (IOException ignored) {
                // Port in use, try next
            }
        }
        throw new IOException("No available port found in range " + startPort + "-" + endPort);
    }

    /**
     * Tool registration holder.
     */
    public record ToolRegistration(Tool tool, ToolHandler handler) {
    }

    /**
     * Functional interface for tool handlers.
     */
    @FunctionalInterface
    public interface ToolHandler {
        CallToolResult handle(Map<String, Object> args);
    }
}
