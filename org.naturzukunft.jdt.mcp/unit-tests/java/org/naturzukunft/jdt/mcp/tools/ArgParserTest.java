package org.naturzukunft.jdt.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ArgParserTest {

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void stringReturnsNullWhenAbsent() {
        assertNull(ArgParser.string(args(), "x"));
        assertNull(ArgParser.string(null, "x"));
    }

    @Test
    void stringCoercesScalars() {
        assertEquals("42", ArgParser.string(args("x", 42), "x"));
        assertEquals("true", ArgParser.string(args("x", true), "x"));
        assertEquals("abc", ArgParser.string(args("x", "abc"), "x"));
    }

    @Test
    void stringRejectsStructuredValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArgParser.string(args("x", List.of("a")), "x"));
        assertTrue(e.getMessage().contains("'x'"), e.getMessage());
        assertTrue(e.getMessage().contains("must be a string"), e.getMessage());
    }

    @Test
    void stringOrDefaultTreatsBlankAsAbsent() {
        assertEquals("ALL", ArgParser.stringOrDefault(args(), "scope", "ALL"));
        assertEquals("ALL", ArgParser.stringOrDefault(args("scope", "  "), "scope", "ALL"));
        assertEquals("PUBLIC", ArgParser.stringOrDefault(args("scope", "PUBLIC"), "scope", "ALL"));
    }

    @Test
    void requiredStringNamesTheParameterAndTheReceivedValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArgParser.requiredString(args("other", "y"), "filePath"));
        assertEquals("Missing required parameter 'filePath' (string). Got filePath=null.", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ArgParser.requiredString(args("filePath", " "), "filePath"));
    }

    @Test
    void requiredIntAcceptsNumbersAndNumericStrings() {
        assertEquals(7, ArgParser.requiredInt(args("offset", 7), "offset"));
        assertEquals(7, ArgParser.requiredInt(args("offset", 7L), "offset"));
        assertEquals(7, ArgParser.requiredInt(args("offset", 7.0), "offset"));
        assertEquals(7, ArgParser.requiredInt(args("offset", " 7 "), "offset"));
    }

    @Test
    void requiredIntRejectsMissingFractionalAndNonNumeric() {
        assertEquals("Missing required parameter 'offset' (integer). Got offset=null.",
                assertThrows(IllegalArgumentException.class,
                        () -> ArgParser.requiredInt(args(), "offset")).getMessage());
        assertEquals("Parameter 'offset' must be an integer, got 7.5 (Double).",
                assertThrows(IllegalArgumentException.class,
                        () -> ArgParser.requiredInt(args("offset", 7.5), "offset")).getMessage());
        assertEquals("Parameter 'offset' must be an integer, got \"abc\" (string).",
                assertThrows(IllegalArgumentException.class,
                        () -> ArgParser.requiredInt(args("offset", "abc"), "offset")).getMessage());
    }

    @Test
    void intOrDefaultFallsBackOnlyWhenAbsent() {
        assertEquals(300, ArgParser.intOrDefault(args(), "timeoutSeconds", 300));
        assertEquals(5, ArgParser.intOrDefault(args("timeoutSeconds", 5), "timeoutSeconds", 300));
        assertThrows(IllegalArgumentException.class,
                () -> ArgParser.intOrDefault(args("timeoutSeconds", "soon"), "timeoutSeconds", 300));
    }

    @Test
    void boolOrDefaultAcceptsBooleansAndBooleanStrings() {
        assertFalse(ArgParser.boolOrDefault(args(), "preview", false));
        assertTrue(ArgParser.boolOrDefault(args(), "preview", true));
        assertTrue(ArgParser.boolOrDefault(args("preview", true), "preview", false));
        assertTrue(ArgParser.boolOrDefault(args("preview", "TRUE"), "preview", false));
        assertFalse(ArgParser.boolOrDefault(args("preview", "false"), "preview", true));
    }

    @Test
    void boolOrDefaultRejectsOtherValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArgParser.boolOrDefault(args("preview", "yes"), "preview", false));
        assertEquals("Parameter 'preview' must be a boolean (true/false), got \"yes\" (string).", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ArgParser.boolOrDefault(args("preview", 1), "preview", false));
    }

    @Test
    void listReturnsDefaultWhenAbsentAndRejectsNonArrays() {
        assertEquals(List.of("*"), ArgParser.list(args(), "methodNames", List.of("*")));
        assertNull(ArgParser.<String>list(args(), "removeParameters", null));
        assertEquals(List.of("a", "b"), ArgParser.list(args("methodNames", List.of("a", "b")), "methodNames", List.of()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArgParser.list(args("methodNames", "a,b"), "methodNames", List.of()));
        assertTrue(e.getMessage().contains("must be an array"), e.getMessage());
    }
}
