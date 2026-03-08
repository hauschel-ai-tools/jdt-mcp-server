package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.naturzukunft.jdt.mcp.server.McpHttpServer;
import org.naturzukunft.jdt.mcp.server.McpProtocolHandler;
import org.naturzukunft.jdt.mcp.server.McpStdioServer;
import org.naturzukunft.jdt.mcp.tools.CodeAnalysisTools;
import org.naturzukunft.jdt.mcp.tools.CodeGenerationTools;
import org.naturzukunft.jdt.mcp.tools.CodeQualityTools;
import org.naturzukunft.jdt.mcp.tools.CreationTools;
import org.naturzukunft.jdt.mcp.tools.DocumentationTools;
import org.naturzukunft.jdt.mcp.tools.ExecutionTools;
import org.naturzukunft.jdt.mcp.tools.NavigationTools;
import org.naturzukunft.jdt.mcp.tools.ProjectInfoTools;
import org.naturzukunft.jdt.mcp.tools.RefactoringTools;

import org.naturzukunft.jdt.mcp.server.ProgressReporter;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Manages the lifecycle of the embedded MCP server.
 */
public class McpServerManager {

    private static final int DEFAULT_PORT_START = 51000;
    private static final int DEFAULT_PORT_END = 51100;

    private int port;
    private boolean running = false;
    private String transport;
    private McpHttpServer httpServer;
    private McpStdioServer stdioServer;
    private McpProtocolHandler protocolHandler;
    private ToolRegistration[] tools;

    /**
     * Starts the MCP server with the configured transport.
     * Transport is determined by system property {@code jdtmcp.transport} (default: stdio).
     */
    public void start() {
        if (running) {
            McpLogger.info("ServerManager", "Server already running");
            return;
        }

        try {
            transport = System.getProperty("jdtmcp.transport", "stdio");

            // Create protocol handler
            protocolHandler = new McpProtocolHandler();

            // Register all tools
            tools = createToolRegistrations();
            protocolHandler.registerTools(tools);

            // Start the appropriate transport
            if ("stdio".equals(transport)) {
                startStdioTransport();
            } else {
                startHttpTransport();
            }

            running = true;
            McpLogger.info("ServerManager", "Server started (" + transport + ") with " + tools.length + " tools");

        } catch (Exception e) {
            McpLogger.error("ServerManager", "Failed to start server", e);
            running = false;
        }
    }

    private void startStdioTransport() {
        stdioServer = new McpStdioServer(protocolHandler);
        protocolHandler.setProgressSender(stdioServer);
        stdioServer.start();
    }

    private void startHttpTransport() throws Exception {
        int portStart = Integer.getInteger("jdtmcp.port.start", DEFAULT_PORT_START);
        int portEnd = Integer.getInteger("jdtmcp.port.end", DEFAULT_PORT_END);
        port = findAvailablePort(portStart, portEnd);

        httpServer = new McpHttpServer(port, protocolHandler);
        protocolHandler.setProgressSender(httpServer);
        httpServer.start();
    }

    private ToolRegistration[] createToolRegistrations() {
        return new ToolRegistration[] {
            // Code Analysis Tools
            CodeAnalysisTools.parseJavaFileTool(),
            CodeAnalysisTools.getTypeHierarchyTool(),
            CodeAnalysisTools.findReferencesTool(),
            CodeAnalysisTools.getSourceRangeTool(),

            // Navigation Tools
            NavigationTools.findTypeTool(),
            NavigationTools.getMethodSignatureTool(),
            NavigationTools.findImplementationsTool(),
            NavigationTools.findCallersTool(),

            // Project Info Tools
            ProjectInfoTools.getVersionTool(),
            ProjectInfoTools.listProjectsTool(),
            ProjectInfoTools.getClasspathTool(),
            ProjectInfoTools.getCompilationErrorsTool(),
            ProjectInfoTools.getProjectStructureTool(),
            ProjectInfoTools.refreshProjectTool(),
            ProjectInfoTools.mavenUpdateProjectTool(),
            ProjectInfoTools.importProjectTool(),
            ProjectInfoTools.removeProjectTool(),
            ProjectInfoTools.reloadWorkspaceTool(),

            // Creation Tools
            CreationTools.createClassTool(),
            CreationTools.createInterfaceTool(),
            CreationTools.createEnumTool(),

            // Code Generation Tools
            CodeGenerationTools.addMethodTool(),
            CodeGenerationTools.addFieldTool(),
            CodeGenerationTools.addImportTool(),
            CodeGenerationTools.implementInterfaceTool(),
            CodeGenerationTools.generateGettersSettersTool(),
            CodeGenerationTools.generateConstructorTool(),
            CodeGenerationTools.generateEqualsHashCodeTool(),
            CodeGenerationTools.generateToStringTool(),
            CodeGenerationTools.generateDelegateMethodsTool(),

            // Refactoring Tools
            RefactoringTools.renameElementTool(),
            RefactoringTools.extractMethodTool(),
            RefactoringTools.moveTypeTool(),
            RefactoringTools.organizeImportsTool(),
            RefactoringTools.inlineTool(),
            RefactoringTools.extractInterfaceTool(),
            RefactoringTools.changeMethodSignatureTool(),
            RefactoringTools.convertToLambdaTool(),
            RefactoringTools.encapsulateFieldTool(),
            RefactoringTools.introduceParameterTool(),

            // Execution Tools
            ExecutionTools.mavenBuildTool(),
            ExecutionTools.runMainTool(),
            ExecutionTools.listTestsTool(),
            ExecutionTools.runTestsTool(),
            ExecutionTools.startTestsAsyncTool(),
            ExecutionTools.getTestResultTool(),

            // Documentation Tools
            DocumentationTools.getJavadocTool(),
            DocumentationTools.getAnnotationsTool(),
            DocumentationTools.findAnnotatedElementsTool(),
            DocumentationTools.generateJavadocTool(),

            // Code Quality Tools
            CodeQualityTools.findUnusedCodeTool(),
            CodeQualityTools.findDeadCodeTool()
        };
    }

    /**
     * Stops the MCP server.
     */
    public void stop() {
        if (!running) {
            return;
        }

        try {
            if (httpServer != null) {
                httpServer.stop();
            }
            if (stdioServer != null) {
                stdioServer.stop();
            }
            running = false;
            McpLogger.info("ServerManager", "Server stopped");
        } catch (Exception e) {
            McpLogger.error("ServerManager", "Error stopping server", e);
        }
    }

    /**
     * Returns true if the server is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the configured transport ("stdio" or "http").
     */
    public String getTransport() {
        return transport;
    }

    /**
     * Returns the port the server is running on (HTTP mode only).
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the stdio server (stdio mode only).
     */
    public McpStdioServer getStdioServer() {
        return stdioServer;
    }

    /**
     * Get registered tools.
     */
    public ToolRegistration[] getTools() {
        return tools;
    }

    /**
     * Get the SSE endpoint URL (HTTP mode only).
     */
    public String getSseEndpoint() {
        return "http://localhost:" + port + "/sse";
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
        /**
         * Handles a tool call with progress reporting support.
         *
         * @param args the tool arguments
         * @param progress reporter for sending progress updates (may be NOOP)
         * @return the tool result
         */
        CallToolResult handle(Map<String, Object> args, ProgressReporter progress);

        /**
         * Creates a ToolHandler from a simple handler that doesn't need progress.
         */
        static ToolHandler simple(SimpleToolHandler handler) {
            return (args, progress) -> handler.handle(args);
        }
    }

    /**
     * Simple tool handler without progress support (for backwards compatibility).
     */
    @FunctionalInterface
    public interface SimpleToolHandler {
        CallToolResult handle(Map<String, Object> args);
    }
}
