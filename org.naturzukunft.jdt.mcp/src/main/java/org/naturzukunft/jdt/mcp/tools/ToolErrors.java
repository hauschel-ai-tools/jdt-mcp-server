package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.Map;

import org.naturzukunft.jdt.mcp.McpLogger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Unified error response builder for all MCP tool methods.
 *
 * Ensures consistent JSON error format across all tools:
 * <pre>
 * {
 *   "status": "ERROR",
 *   "message": "Error during <toolName>: <exception message>",
 *   "exceptionType": "IllegalArgumentException",
 *   "cause": "root cause message"  // only if cause exists
 * }
 * </pre>
 *
 * All errors are logged via {@link McpLogger}.
 *
 * @see <a href="https://git.changinggraph.org/ai-tools/jdt-mcp-server/issues/32">#32</a>
 */
class ToolErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolErrors() {
        // utility class
    }

    /**
     * Creates a unified error response for exception-based tool failures.
     * Logs the error and returns a JSON-formatted {@link CallToolResult}.
     *
     * @param toolName short tool identifier for logging and message (e.g. "extract method", "rename")
     * @param e the exception that occurred
     * @return a {@link CallToolResult} with isError=true and structured JSON body
     */
    static CallToolResult errorResult(String toolName, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        McpLogger.error(toolName, toolName + " failed: " + msg, e);

        Map<String, Object> error = new HashMap<>();
        error.put("status", "ERROR");
        error.put("message", "Error during " + toolName + ": " + msg);
        error.put("exceptionType", e.getClass().getSimpleName());
        if (e.getCause() != null) {
            error.put("cause", e.getCause().toString());
        }

        try {
            return new CallToolResult(MAPPER.writeValueAsString(error), true);
        } catch (Exception ex) {
            return new CallToolResult("Error during " + toolName + ": " + msg, true);
        }
    }
}
