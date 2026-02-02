package org.naturzukunft.jdt.mcp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.naturzukunft.jdt.mcp.McpLogger;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolHandler;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Handles MCP protocol messages (JSON-RPC 2.0).
 */
public class McpProtocolHandler {

    private static final String JSONRPC_VERSION = "2.0";
    private static final String MCP_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "Eclipse JDT MCP Server";
    private static final String SERVER_VERSION = "1.0.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolRegistration> tools = new HashMap<>();

    // For sending progress notifications
    private ProgressNotificationSender progressSender;

    /**
     * Sets the progress notification sender.
     */
    public void setProgressSender(ProgressNotificationSender sender) {
        this.progressSender = sender;
    }

    /**
     * Register a tool.
     */
    public void registerTool(ToolRegistration registration) {
        tools.put(registration.tool().name(), registration);
    }

    /**
     * Register multiple tools.
     */
    public void registerTools(ToolRegistration... registrations) {
        for (ToolRegistration reg : registrations) {
            registerTool(reg);
        }
    }

    /**
     * Handle an incoming JSON-RPC message and return the response.
     */
    public String handleMessage(String jsonMessage) {
        try {
            JsonNode request = mapper.readTree(jsonMessage);

            // Validate JSON-RPC version
            String jsonrpc = request.path("jsonrpc").asText();
            if (!JSONRPC_VERSION.equals(jsonrpc)) {
                return createErrorResponse(null, -32600, "Invalid Request: jsonrpc must be '2.0'");
            }

            // Get request fields
            JsonNode idNode = request.get("id");
            String method = request.path("method").asText();
            JsonNode params = request.get("params");

            // Handle method
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "initialized" -> handleInitialized(params);
                case "tools/list" -> handleToolsList(params);
                case "tools/call" -> handleToolsCall(params);
                case "ping" -> handlePing(params);
                default -> throw new McpException(-32601, "Method not found: " + method);
            };

            // For notifications (no id), don't send response
            if (idNode == null || idNode.isNull()) {
                return null;
            }

            return createSuccessResponse(idNode, result);

        } catch (McpException e) {
            return createErrorResponse(null, e.getCode(), e.getMessage());
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error handling message: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Handle initialize request.
     */
    private Map<String, Object> handleInitialize(JsonNode params) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", MCP_VERSION);

        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);

        Map<String, Object> capabilities = new HashMap<>();

        // Tools capability
        Map<String, Object> toolsCapability = new HashMap<>();
        toolsCapability.put("listChanged", false);
        capabilities.put("tools", toolsCapability);

        // Progress notification capability - server supports sending progress
        // MCP protocol expects empty object {} for capability flags, not boolean
        capabilities.put("experimental", Map.of("progress", Map.of()));

        result.put("capabilities", capabilities);

        System.out.println("[JDT MCP] Client initialized");
        return result;
    }

    /**
     * Handle initialized notification.
     */
    private Object handleInitialized(JsonNode params) {
        System.out.println("[JDT MCP] Client initialization complete");
        return null; // Notification, no response
    }

    /**
     * Handle tools/list request.
     */
    private Map<String, Object> handleToolsList(JsonNode params) {
        List<Map<String, Object>> toolList = new ArrayList<>();

        for (ToolRegistration reg : tools.values()) {
            Tool tool = reg.tool();
            Map<String, Object> toolInfo = new HashMap<>();
            toolInfo.put("name", tool.name());
            toolInfo.put("description", tool.description());

            // Convert input schema
            if (tool.inputSchema() != null) {
                Map<String, Object> inputSchema = new HashMap<>();
                inputSchema.put("type", tool.inputSchema().type());

                if (tool.inputSchema().properties() != null) {
                    inputSchema.put("properties", tool.inputSchema().properties());
                }
                if (tool.inputSchema().required() != null) {
                    inputSchema.put("required", tool.inputSchema().required());
                }

                toolInfo.put("inputSchema", inputSchema);
            }

            toolList.add(toolInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tools", toolList);
        return result;
    }

    /**
     * Handle tools/call request.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(JsonNode params) throws McpException {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.get("arguments");

        // Extract progressToken from _meta if present
        JsonNode metaNode = params.get("_meta");
        String progressToken = null;
        if (metaNode != null && metaNode.has("progressToken")) {
            progressToken = metaNode.get("progressToken").asText();
        }

        ToolRegistration registration = tools.get(toolName);
        if (registration == null) {
            throw new McpException(-32602, "Unknown tool: " + toolName);
        }

        // Convert arguments to Map
        Map<String, Object> args = new HashMap<>();
        if (arguments != null && arguments.isObject()) {
            try {
                args = mapper.convertValue(arguments, Map.class);
            } catch (Exception e) {
                throw new McpException(-32602, "Invalid arguments: " + e.getMessage());
            }
        }

        McpLogger.info("Protocol", "Calling tool: " + toolName);
        McpLogger.debug("Protocol", "Tool args: " + args);
        McpLogger.debug("Protocol", "progressToken: " + progressToken + ", progressSender: " + (progressSender != null ? "set" : "null"));

        // Create progress reporter
        // If client didn't send a progressToken, generate one server-side
        // This is a workaround for Claude Code not sending progressToken
        final String token = progressToken != null ? progressToken : "server-generated-" + System.currentTimeMillis();
        if (progressToken == null) {
            McpLogger.debug("Protocol", "Client didn't send progressToken, using server-generated: " + token);
        }
        ProgressReporter progressReporter = (progressSender != null)
            ? (current, total, message) -> {
                try {
                    progressSender.sendProgress(token, current, total, message);
                    McpLogger.debug("Protocol", "Sent progress: " + current + "/" + total + " - " + message);
                } catch (Exception e) {
                    McpLogger.warn("Protocol", "Error sending progress: " + e.getMessage());
                }
            }
            : ProgressReporter.NOOP;

        // Call the tool handler
        try {
            long startTime = System.currentTimeMillis();
            CallToolResult toolResult = registration.handler().handle(args, progressReporter);
            long duration = System.currentTimeMillis() - startTime;

            McpLogger.info("Protocol", "Tool " + toolName + " completed in " + duration + "ms, isError=" + toolResult.isError());

            Map<String, Object> result = new HashMap<>();

            // Create content array
            List<Map<String, Object>> contentList = new ArrayList<>();
            int contentCount = 0;
            for (Content contentItem : toolResult.content()) {
                contentCount++;
                Map<String, Object> contentMap = new HashMap<>();
                if (contentItem instanceof TextContent tc) {
                    contentMap.put("type", "text");
                    contentMap.put("text", tc.text());
                    McpLogger.debug("Protocol", "Content item " + contentCount + " text length: " +
                            (tc.text() != null ? tc.text().length() : 0));
                } else {
                    contentMap.put("type", "text");
                    contentMap.put("text", contentItem.toString());
                }
                contentList.add(contentMap);
            }

            if (contentList.isEmpty()) {
                McpLogger.warn("Protocol", "Tool " + toolName + " returned EMPTY content list!");
            }

            result.put("content", contentList);
            result.put("isError", toolResult.isError());

            McpLogger.debug("Protocol", "Returning result with " + contentList.size() + " content items");
            return result;

        } catch (Exception e) {
            McpLogger.error("Protocol", "Tool " + toolName + " threw exception", e);

            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", "Error: " + e.getMessage());
            content.add(textContent);
            result.put("content", content);
            result.put("isError", true);
            return result;
        }
    }

    /**
     * Handle ping request.
     */
    private Map<String, Object> handlePing(JsonNode params) {
        return new HashMap<>(); // Empty response for ping
    }

    /**
     * Create a JSON-RPC success response.
     */
    private String createSuccessResponse(JsonNode id, Object result) {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", JSONRPC_VERSION);

            if (id != null) {
                response.set("id", id);
            }

            if (result != null) {
                response.set("result", mapper.valueToTree(result));
            } else {
                response.putNull("result");
            }

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Error creating response\"}}";
        }
    }

    /**
     * Create a JSON-RPC error response.
     */
    private String createErrorResponse(JsonNode id, int code, String message) {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", JSONRPC_VERSION);

            if (id != null) {
                response.set("id", id);
            } else {
                response.putNull("id");
            }

            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            response.set("error", error);

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Error creating error response\"}}";
        }
    }

    /**
     * MCP protocol exception.
     */
    private static class McpException extends Exception {
        private final int code;

        public McpException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
