package org.naturzukunft.jdt.mcp;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle for the Eclipse JDT MCP Server.
 */
public class Activator implements BundleActivator {

    public static final String PLUGIN_ID = "org.naturzukunft.jdt.mcp";

    private static Activator plugin;
    private McpServerManager mcpServerManager;

    public Activator() {
    }

    @Override
    public void start(BundleContext context) throws Exception {
        plugin = this;

        // Initialize logger first
        McpLogger.init();
        McpLogger.info("Activator", "Plugin activated");

        // In headless mode, HeadlessApplication controls server lifecycle
        // (imports projects first, then starts server)
        if (!"true".equals(System.getProperty("jdtmcp.headless"))) {
            startMcpServer();
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        stopMcpServer();
        plugin = null;
        McpLogger.info("Activator", "Plugin deactivated");
    }

    /**
     * Starts the MCP server if enabled in preferences.
     */
    public void startMcpServer() {
        if (mcpServerManager == null) {
            mcpServerManager = new McpServerManager();
        }
        if (!mcpServerManager.isRunning()) {
            mcpServerManager.start();
        }
    }

    /**
     * Stops the MCP server if running.
     */
    public void stopMcpServer() {
        if (mcpServerManager != null && mcpServerManager.isRunning()) {
            mcpServerManager.stop();
        }
    }

    /**
     * Restarts the MCP server.
     */
    public void restartMcpServer() {
        stopMcpServer();
        startMcpServer();
    }

    /**
     * Returns the MCP server manager.
     */
    public McpServerManager getMcpServerManager() {
        return mcpServerManager;
    }

    /**
     * Returns the shared instance.
     */
    public static Activator getDefault() {
        return plugin;
    }
}
