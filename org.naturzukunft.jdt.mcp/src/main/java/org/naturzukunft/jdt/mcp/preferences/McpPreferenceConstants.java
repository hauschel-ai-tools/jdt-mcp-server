package org.naturzukunft.jdt.mcp.preferences;

/**
 * Constants for MCP server preferences.
 */
public final class McpPreferenceConstants {

    private McpPreferenceConstants() {
        // Utility class
    }

    /** Preference key: Enable/disable MCP server */
    public static final String PREF_SERVER_ENABLED = "mcp.server.enabled";

    /** Preference key: Port range start */
    public static final String PREF_PORT_START = "mcp.server.port.start";

    /** Preference key: Port range end */
    public static final String PREF_PORT_END = "mcp.server.port.end";

    /** Default value: Server enabled */
    public static final boolean DEFAULT_SERVER_ENABLED = false;

    /** Default value: Port range start */
    public static final int DEFAULT_PORT_START = 51000;

    /** Default value: Port range end */
    public static final int DEFAULT_PORT_END = 51100;
}
