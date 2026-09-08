package org.naturzukunft.jdt.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * Typed access to tool-call arguments with self-explanatory failures.
 *
 * <p>Tool handlers receive their arguments as a loosely typed {@code Map<String, Object>}
 * (Jackson's view of the JSON object). Casting straight out of that map turns a wrong or
 * missing value into a {@code NullPointerException} or {@code ClassCastException} whose message
 * tells an AI client nothing it could correct. Every accessor here instead throws an
 * {@link IllegalArgumentException} naming the parameter, the expected type and the value
 * actually received, and coerces the harmless cases (a number sent as {@code "42"}, a boolean
 * sent as {@code "true"}, a number where a string is expected) instead of failing.
 *
 * <p>Presence of <em>required</em> parameters is enforced centrally before a handler runs
 * (see {@code ToolArgumentValidator}); the {@code required*} accessors here are the second
 * line of defence for handlers that are called with a hand-built map.
 *
 * <p>Pure Java, no Eclipse/OSGi dependency: unit-tested via {@code tests/run-unit-tests.sh}.
 *
 * @see <a href="https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/57">#57</a>
 */
public final class ArgParser {

    private ArgParser() {
        // utility class
    }

    /**
     * Optional string parameter. Returns {@code null} when absent. A number or boolean value is
     * rendered as text; any other non-string value (object, array) is rejected.
     */
    public static String string(Map<String, Object> args, String name) {
        Object value = raw(args, name);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw typeMismatch(name, "a string", value);
    }

    /**
     * Optional string parameter with a default for the absent or blank case.
     */
    public static String stringOrDefault(Map<String, Object> args, String name, String defaultValue) {
        String value = string(args, name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * Required string parameter: absent or blank values are rejected.
     */
    public static String requiredString(Map<String, Object> args, String name) {
        String value = string(args, name);
        if (value == null || value.isBlank()) {
            throw missing(name, "string");
        }
        return value;
    }

    /**
     * Required integer parameter. Accepts JSON numbers without a fractional part and numeric
     * strings such as {@code "42"}.
     */
    public static int requiredInt(Map<String, Object> args, String name) {
        Object value = raw(args, name);
        if (value == null) {
            throw missing(name, "integer");
        }
        return toInt(name, value);
    }

    /**
     * Optional integer parameter with a default for the absent case.
     */
    public static int intOrDefault(Map<String, Object> args, String name, int defaultValue) {
        Object value = raw(args, name);
        return value == null ? defaultValue : toInt(name, value);
    }

    /**
     * Optional boolean parameter with a default for the absent case. Accepts JSON booleans and
     * the strings {@code "true"} / {@code "false"} (case-insensitive).
     */
    public static boolean boolOrDefault(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = raw(args, name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String normalized = s.trim();
            if (normalized.equalsIgnoreCase("true")) {
                return true;
            }
            if (normalized.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw typeMismatch(name, "a boolean (true/false)", value);
    }

    /**
     * Optional list parameter with a default for the absent case. Element types are not
     * checked (Jackson already produced plain Java values); a non-array value is rejected.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> list(Map<String, Object> args, String name, List<T> defaultValue) {
        Object value = raw(args, name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof List<?> l) {
            return (List<T>) l;
        }
        throw typeMismatch(name, "an array", value);
    }

    private static Object raw(Map<String, Object> args, String name) {
        return args == null ? null : args.get(name);
    }

    private static int toInt(String name, Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw typeMismatch(name, "an integer", value);
            }
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw typeMismatch(name, "an integer", value);
            }
        }
        throw typeMismatch(name, "an integer", value);
    }

    private static IllegalArgumentException missing(String name, String type) {
        return new IllegalArgumentException(
                "Missing required parameter '" + name + "' (" + type + "). Got " + name + "=null.");
    }

    private static IllegalArgumentException typeMismatch(String name, String expected, Object value) {
        return new IllegalArgumentException(
                "Parameter '" + name + "' must be " + expected + ", got " + describe(value) + ".");
    }

    private static String describe(Object value) {
        if (value instanceof String s) {
            return "\"" + s + "\" (string)";
        }
        return value + " (" + value.getClass().getSimpleName() + ")";
    }
}
