package org.fixture.core;

/**
 * Utility class to be moved to org.fixture.core.internal via moveType.
 */
public class HelperUtil {

    public static String sanitize(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase();
    }
}
