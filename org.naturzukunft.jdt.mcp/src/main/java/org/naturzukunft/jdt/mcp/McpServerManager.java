package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.naturzukunft.jdt.mcp.preferences.McpPreferenceConstants;
import org.naturzukunft.jdt.mcp.server.McpHttpServer;
import org.naturzukunft.jdt.mcp.server.McpProtocolHandler;
import org.naturzukunft.jdt.mcp.tools.CodeAnalysisTools;
import org.naturzukunft.jdt.mcp.tools.CodeGenerationTools;
import org.naturzukunft.jdt.mcp.tools.CodeQualityTools;
import org.naturzukunft.jdt.mcp.tools.CreationTools;
import org.naturzukunft.jdt.mcp.tools.DocumentationTools;
import org.naturzukunft.jdt.mcp.tools.ExecutionTools;
import org.naturzukunft.jdt.mcp.tools.NavigationTools;
import org.naturzukunft.jdt.mcp.tools.ProjectInfoTools;
import org.naturzukunft.jdt.mcp.tools.RefactoringTools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Manages the lifecycle of the embedded MCP server.
 */
public class McpServerManager {

    private int port;
    private boolean running = false;
    private McpHttpServer httpServer;
    private McpProtocolHandler protocolHandler;

    // Tool registrations
    private ToolRegistration[] tools;

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

            // Create protocol handler
            protocolHandler = new McpProtocolHandler();

            // Register all tools
            tools = new ToolRegistration[] {
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
                ProjectInfoTools.listProjectsTool(),
                ProjectInfoTools.getClasspathTool(),
                ProjectInfoTools.getCompilationErrorsTool(),
                ProjectInfoTools.getProjectStructureTool(),
                ProjectInfoTools.refreshProjectTool(),

                // Creation Tools
                CreationTools.createClassTool(),
                CreationTools.createInterfaceTool(),
                CreationTools.createEnumTool(),

                // Code Generation Tools
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
                ExecutionTools.listUnitTestsTool(),
                ExecutionTools.listIntegrationTestsTool(),
                ExecutionTools.runUnitTestsTool(),
                ExecutionTools.runIntegrationTestsTool(),

                // Documentation Tools
                DocumentationTools.getJavadocTool(),
                DocumentationTools.getAnnotationsTool(),
                DocumentationTools.findAnnotatedElementsTool(),
                DocumentationTools.generateJavadocTool(),

                // Code Quality Tools
                CodeQualityTools.quickFixTool(),
                CodeQualityTools.findUnusedCodeTool(),
                CodeQualityTools.findDeadCodeTool()
            };

            // Register tools with protocol handler
            protocolHandler.registerTools(tools);

            // Create and start HTTP server
            httpServer = new McpHttpServer(port, protocolHandler);
            httpServer.start();

            running = true;
            System.out.println("[JDT MCP] Server started successfully");
            System.out.println("[JDT MCP] Registered " + tools.length + " tools:");
            for (ToolRegistration tool : tools) {
                System.out.println("[JDT MCP]   - " + tool.tool().name());
            }

        } catch (Exception e) {
            System.err.println("[JDT MCP] Failed to start server: " + e.getMessage());
            e.printStackTrace();
            running = false;
        }
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
     * Get registered tools (for testing/debugging).
     */
    public ToolRegistration[] getTools() {
        return tools;
    }

    /**
     * Get the SSE endpoint URL.
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
        CallToolResult handle(Map<String, Object> args);
    }
}
