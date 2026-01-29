package org.naturzukunft.jdt.mcp.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.naturzukunft.jdt.mcp.Activator;

/**
 * Initializes default preference values for the MCP server.
 */
public class McpPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        store.setDefault(McpPreferenceConstants.PREF_SERVER_ENABLED,
                McpPreferenceConstants.DEFAULT_SERVER_ENABLED);
        store.setDefault(McpPreferenceConstants.PREF_PORT_START,
                McpPreferenceConstants.DEFAULT_PORT_START);
        store.setDefault(McpPreferenceConstants.PREF_PORT_END,
                McpPreferenceConstants.DEFAULT_PORT_END);
    }
}
