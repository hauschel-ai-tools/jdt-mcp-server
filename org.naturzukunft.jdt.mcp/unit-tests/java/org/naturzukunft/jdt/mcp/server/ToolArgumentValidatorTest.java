package org.naturzukunft.jdt.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ToolArgumentValidatorTest {

    private static final Map<String, Object> FIND_TYPE_PROPERTIES = Map.of(
            "pattern", Map.of("type", "string", "description", "Type name pattern (e.g., '*Service')"));

    private static final Map<String, Object> EXTRACT_PROPERTIES = Map.of(
            "filePath", Map.of("type", "string", "description", "Path to the file"),
            "startOffset", Map.of("type", "integer", "description", "Start offset"),
            "preview", Map.of("type", "boolean", "description", "Preview only"));

    @Test
    void validCallProducesNoReport() {
        assertTrue(ToolArgumentValidator.validate("jdt_find_type", List.of("pattern"),
                FIND_TYPE_PROPERTIES, Map.of("pattern", "*Service")).isEmpty());
    }

    @Test
    void noRequiredParametersMeansAnythingGoes() {
        assertTrue(ToolArgumentValidator.validate("jdt_list_projects", null, null, null).isEmpty());
        assertTrue(ToolArgumentValidator.validate("jdt_list_projects", List.of(), Map.of(), Map.of("x", 1)).isEmpty());
    }

    @Test
    void missingRequiredParameterIsReported() {
        Optional<Map<String, Object>> report = ToolArgumentValidator.validate("jdt_find_type",
                List.of("pattern"), FIND_TYPE_PROPERTIES, Map.of());
        assertTrue(report.isPresent());
        assertEquals("ERROR", report.get().get("status"));
        assertEquals(List.of("pattern"), report.get().get("missingParameters"));
        assertEquals(List.of(), report.get().get("unknownParameters"));
        assertEquals(Map.of("pattern", "<string>"), report.get().get("example"));
        String message = (String) report.get().get("message");
        assertTrue(message.startsWith("Missing required parameter for jdt_find_type: pattern."), message);
        assertTrue(message.contains("Accepted parameters: pattern (string, required)."), message);
    }

    @Test
    void blankStringCountsAsMissing() {
        assertTrue(ToolArgumentValidator.validate("jdt_find_type", List.of("pattern"),
                FIND_TYPE_PROPERTIES, Map.of("pattern", "   ")).isPresent());
    }

    @Test
    void wronglyNamedParameterIsCalledOut() {
        Map<String, Object> report = ToolArgumentValidator.validate("jdt_find_type",
                List.of("pattern"), FIND_TYPE_PROPERTIES, Map.of("typeName", "Foo")).orElseThrow();
        assertEquals(List.of("typeName"), report.get("unknownParameters"));
        assertEquals(Map.of("typeName", "Foo"), report.get("received"));
        String message = (String) report.get("message");
        assertTrue(message.contains("Received unknown parameter typeName -- this tool does NOT accept that name"), message);
    }

    @Test
    void reportDescribesEveryAcceptedParameterWithTypeAndRequiredFlag() {
        Map<String, Object> report = ToolArgumentValidator.validate("jdt_extract_method",
                List.of("filePath", "startOffset"), EXTRACT_PROPERTIES, Map.of("filePath", "A.java")).orElseThrow();
        assertEquals(List.of("startOffset"), report.get("missingParameters"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> accepted = (Map<String, Map<String, Object>>) report.get("acceptedParameters");
        assertEquals(3, accepted.size());
        assertEquals("integer", accepted.get("startOffset").get("type"));
        assertEquals(true, accepted.get("startOffset").get("required"));
        assertEquals("Start offset", accepted.get("startOffset").get("description"));
        assertEquals(false, accepted.get("preview").get("required"));

        assertEquals(Map.of("filePath", "<string>", "startOffset", 0), report.get("example"));
    }

    @Test
    void pluralWordingForSeveralMissingParameters() {
        String message = (String) ToolArgumentValidator.validate("jdt_extract_method",
                List.of("filePath", "startOffset"), EXTRACT_PROPERTIES, Map.of()).orElseThrow().get("message");
        assertTrue(message.startsWith("Missing required parameters for jdt_extract_method: filePath, startOffset."), message);
    }

    @Test
    void longDescriptionsAreTruncated() {
        String longText = "x".repeat(500);
        Map<String, Object> report = ToolArgumentValidator.validate("t", List.of("a"),
                Map.of("a", Map.of("type", "string", "description", longText)), Map.of()).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> accepted = (Map<String, Map<String, Object>>) report.get("acceptedParameters");
        String description = (String) accepted.get("a").get("description");
        assertEquals(200, description.length());
        assertTrue(description.endsWith("…"));
    }
}
