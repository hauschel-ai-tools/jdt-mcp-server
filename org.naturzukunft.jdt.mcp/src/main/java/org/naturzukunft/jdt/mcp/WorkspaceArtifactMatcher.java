package org.naturzukunft.jdt.mcp;

import java.util.Collection;

/**
 * Matches a JAR file name from the local Maven repository against the artifactIds of the
 * projects open in the workspace.
 *
 * <p>A reactor sibling that is open in the workspace must win over its installed
 * {@code ~/.m2} JAR: otherwise edits in the sibling only become visible after
 * {@code mvn install}, and the JAR collides with the project reference for the same module
 * ("Build path contains duplicate entry", see issue #116).
 *
 * <p>Deliberately free of Eclipse/OSGi API so it can be unit-tested with plain JUnit
 * (see {@code tests/run-unit-tests.sh}).
 */
public final class WorkspaceArtifactMatcher {

    private WorkspaceArtifactMatcher() {
        // utility class
    }

    /**
     * Returns the artifactId from {@code artifactIds} that {@code jarFileName} is the build
     * output of, or {@code null} when the JAR belongs to none of them.
     *
     * <p>A JAR belongs to an artifactId when its name starts with {@code artifactId + "-"}
     * and the remainder is that artifact's version. The prefix test alone is ambiguous
     * whenever one artifactId is a prefix of another ({@code foo} vs. {@code foo-bar}:
     * {@code foo-bar-1.0.jar} starts with {@code foo-}), so the remainder has to look like a
     * version: it either starts with a digit, or - for versions such as {@code RELEASE} -
     * carries no further {@code -} segment that could belong to a longer artifactId. Among
     * several candidates the longest artifactId wins. Matching too eagerly is the worse
     * failure here: it would swap a third-party JAR for an unrelated workspace project.
     *
     * @param jarFileName the file name of the JAR, without directories
     * @param artifactIds the artifactIds of the workspace projects
     * @return the matching artifactId, or {@code null}
     */
    public static String matchArtifactId(String jarFileName, Collection<String> artifactIds) {
        if (jarFileName == null || jarFileName.isEmpty() || artifactIds == null) {
            return null;
        }

        String versioned = null;
        String fallback = null;

        for (String artifactId : artifactIds) {
            if (artifactId == null || artifactId.isEmpty()) {
                continue;
            }
            String prefix = artifactId + "-";
            if (!jarFileName.startsWith(prefix)) {
                continue;
            }
            String remainder = jarFileName.substring(prefix.length());
            if (remainder.isEmpty()) {
                continue;
            }
            if (Character.isDigit(remainder.charAt(0))) {
                if (versioned == null || artifactId.length() > versioned.length()) {
                    versioned = artifactId;
                }
            } else if (remainder.indexOf('-') < 0
                    && (fallback == null || artifactId.length() > fallback.length())) {
                fallback = artifactId;
            }
        }

        return versioned != null ? versioned : fallback;
    }
}
