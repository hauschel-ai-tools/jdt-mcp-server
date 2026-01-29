package org.naturzukunft.jdt.mcp;

import org.eclipse.ui.IStartup;
import org.naturzukunft.jdt.mcp.preferences.McpPreferenceConstants;

/**
 * Early startup handler to start the MCP server when Eclipse launches.
 */
public class EarlyStartup implements IStartup {

    @Override
    public void earlyStartup() {
        System.out.println("[JDT MCP] Early startup triggered");

        // Check if MCP server is enabled in preferences
        boolean enabled = Activator.getDefault()
                .getPreferenceStore()
                .getBoolean(McpPreferenceConstants.PREF_SERVER_ENABLED);

        if (enabled) {
            System.out.println("[JDT MCP] Server enabled in preferences, starting...");
            Activator.getDefault().startMcpServer();
        } else {
            System.out.println("[JDT MCP] Server disabled in preferences");
        }
    }
}
