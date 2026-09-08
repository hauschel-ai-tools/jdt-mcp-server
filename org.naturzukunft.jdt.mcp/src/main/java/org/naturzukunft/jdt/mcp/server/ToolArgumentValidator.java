package org.naturzukunft.jdt.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Checks a tool call's arguments against the tool's declared input schema before the handler
 * runs, so a missing required parameter never reaches handler code as {@code null}.
 *
 * <p>The MCP {@code inputSchema} declares {@code required} names and typed {@code properties},
 * but only clients enforce it -- and AI clients regularly guess parameter names
 * ({@code typeName} instead of {@code pattern}). A {@code NullPointerException} or an
 * {@code Element not found: null} is nothing such a client can learn from. The violation
 * report produced here is meant to be self-healing: it names what is missing, which of the
 * received names the tool does not know, every accepted parameter with type and description,
 * the arguments actually received, and an example call.
 *
 * <p>Pure Java, no Jackson/Eclipse dependency: the report is a plain map the protocol layer
 * serialises. Unit-tested via {@code tests/run-unit-tests.sh}.
 *
 * @see <a href="https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/57">#57</a>
 */
public final class ToolArgumentValidator {

    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private ToolArgumentValidator() {
        // utility class
    }

    /**
     * Validates that every required parameter is present and non-blank.
     *
     * @param toolName   the tool being called (for the message)
     * @param required   required parameter names from the schema, may be {@code null}
     * @param properties schema properties ({@code name -> {type, description}}), may be {@code null}
     * @param args       the arguments received, may be {@code null}
     * @return an empty optional when the call is valid, otherwise a structured violation report
     *         ({@code status}, {@code message}, {@code missingParameters}, {@code unknownParameters},
     *         {@code acceptedParameters}, {@code received}, {@code example})
     */
    public static Optional<Map<String, Object>> validate(String toolName, List<String> required,
            Map<String, Object> properties, Map<String, Object> args) {
        Map<String, Object> received = args == null ? Map.of() : args;
        List<String> requiredNames = required == null ? List.of() : required;
        Map<String, Object> knownProperties = properties == null ? Map.of() : properties;

        List<String> missing = new ArrayList<>();
        for (String name : requiredNames) {
            Object value = received.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }

        List<String> unknown = new ArrayList<>();
        for (String name : received.keySet()) {
            if (!knownProperties.containsKey(name)) {
                unknown.add(name);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "ERROR");
        report.put("message", buildMessage(toolName, missing, unknown, requiredNames, knownProperties));
        report.put("missingParameters", missing);
        report.put("unknownParameters", unknown);
        report.put("acceptedParameters", describeAccepted(requiredNames, knownProperties));
        report.put("received", new TreeMap<>(received));
        report.put("example", buildExample(requiredNames, knownProperties));
        return Optional.of(report);
    }

    private static String buildMessage(String toolName, List<String> missing, List<String> unknown,
            List<String> required, Map<String, Object> properties) {
        StringBuilder sb = new StringBuilder();
        sb.append("Missing required parameter").append(missing.size() == 1 ? "" : "s")
          .append(" for ").append(toolName).append(": ").append(String.join(", ", missing)).append(". ");
        if (!unknown.isEmpty()) {
            sb.append("Received unknown parameter").append(unknown.size() == 1 ? "" : "s")
              .append(" ").append(String.join(", ", unknown))
              .append(" -- this tool does NOT accept ").append(unknown.size() == 1 ? "that name" : "those names")
              .append("; use the exact names below. ");
        }
        sb.append("Accepted parameters: ");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String name = entry.getKey();
            parts.add(name + " (" + typeOf(entry.getValue()) + (required.contains(name) ? ", required" : "") + ")");
        }
        sb.append(parts.isEmpty() ? "none" : String.join(", ", parts)).append(".");
        return sb.toString();
    }

    private static Map<String, Object> describeAccepted(List<String> required, Map<String, Object> properties) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", typeOf(entry.getValue()));
            info.put("required", required.contains(entry.getKey()));
            String description = descriptionOf(entry.getValue());
            if (description != null) {
                info.put("description", description);
            }
            accepted.put(entry.getKey(), info);
        }
        return accepted;
    }

    private static Map<String, Object> buildExample(List<String> required, Map<String, Object> properties) {
        Map<String, Object> example = new LinkedHashMap<>();
        for (String name : required) {
            example.put(name, placeholderFor(typeOf(properties.get(name))));
        }
        return example;
    }

    private static Object placeholderFor(String type) {
        return switch (type) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> "<" + type + ">";
        };
    }

    private static String typeOf(Object property) {
        if (property instanceof Map<?, ?> m && m.get("type") instanceof String type) {
            return type;
        }
        return "string";
    }

    private static String descriptionOf(Object property) {
        if (property instanceof Map<?, ?> m && m.get("description") instanceof String description) {
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                return description.substring(0, MAX_DESCRIPTION_LENGTH - 1) + "…";
            }
            return description;
        }
        return null;
    }
}
