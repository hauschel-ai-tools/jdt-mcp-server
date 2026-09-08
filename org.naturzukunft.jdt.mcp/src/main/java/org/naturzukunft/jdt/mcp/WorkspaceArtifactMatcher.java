package org.naturzukunft.jdt.mcp;

import java.util.Map;

/**
 * Matches a JAR resolved from the local Maven repository against the Maven modules that are
 * open in the workspace.
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
     * Returns the artifactId of the workspace module that {@code jarPath} is the build output
     * of, or {@code null} when the JAR belongs to none of them.
     *
     * <p>Two ways to decide, in this order:
     *
     * <ol>
     * <li><b>By coordinates.</b> A path inside a Maven repository carries the full
     * coordinates: {@code .../<groupId as directories>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].jar}.
     * When the path has that shape, groupId <em>and</em> artifactId both have to match a
     * workspace module - a foreign artifact that merely shares a common artifactId
     * ({@code core}, {@code common}, {@code utils}) is rejected. The decision is final: a
     * repository path that does not name a workspace module belongs to no workspace module.
     * <li><b>By file name.</b> Only for JARs outside that layout (system-scope JARs, JARs
     * from a flat directory), where no groupId is available. The name has to start with
     * {@code artifactId + "-"} followed by something version-like: a digit, or - for
     * versions such as {@code RELEASE} - no further {@code -} segment that could belong to a
     * longer artifactId. Among several candidates the longest artifactId wins. Without a
     * groupId this stays a heuristic; matching too eagerly is the worse failure, because the
     * caller drops the JAR in favour of a project reference.
     * </ol>
     *
     * @param jarPath the path of the JAR, absolute or relative
     * @param groupIdsByArtifactId artifactId to groupId of every Maven module in the
     *        workspace; a {@code null} value means the groupId could not be read, which
     *        disables the groupId check for that module
     * @return the matching artifactId, or {@code null}
     */
    public static String matchArtifactId(String jarPath, Map<String, String> groupIdsByArtifactId) {
        if (jarPath == null || jarPath.isEmpty() || groupIdsByArtifactId == null
                || groupIdsByArtifactId.isEmpty()) {
            return null;
        }

        String[] segments = jarPath.replace('\\', '/').split("/");
        String fileName = segments[segments.length - 1];
        if (fileName.isEmpty()) {
            return null;
        }

        // A path of the form .../<group>/<artifactId>/<version>/<artifactId>-<version>...
        // identifies the artifact by itself, no guessing from the file name needed.
        if (segments.length >= 4) {
            String artifactId = segments[segments.length - 3];
            String version = segments[segments.length - 2];
            String expectedPrefix = artifactId + "-" + version;
            if (fileName.length() > expectedPrefix.length()
                    && fileName.startsWith(expectedPrefix)
                    && isCoordinateBoundary(fileName.charAt(expectedPrefix.length()))) {
                if (!groupIdsByArtifactId.containsKey(artifactId)) {
                    return null;
                }
                String groupId = groupIdsByArtifactId.get(artifactId);
                String groupPath = String.join(".",
                        java.util.Arrays.copyOfRange(segments, 0, segments.length - 3));
                if (groupId == null || groupId.isEmpty() || matchesGroupId(groupPath, groupId)) {
                    return artifactId;
                }
                return null;
            }
        }

        return matchByFileName(fileName, groupIdsByArtifactId);
    }

    /** A version in a JAR name is followed by the extension or by a classifier. */
    private static boolean isCoordinateBoundary(char c) {
        return c == '.' || c == '-';
    }

    /**
     * The directory segments above the artifactId, joined with dots, end with the groupId -
     * everything before that is the repository root, which is unknown here.
     */
    private static boolean matchesGroupId(String groupPath, String groupId) {
        return groupPath.equals(groupId) || groupPath.endsWith("." + groupId);
    }

    private static String matchByFileName(String fileName, Map<String, String> groupIdsByArtifactId) {
        String versioned = null;
        String fallback = null;

        for (String artifactId : groupIdsByArtifactId.keySet()) {
            if (artifactId == null || artifactId.isEmpty()) {
                continue;
            }
            String prefix = artifactId + "-";
            if (!fileName.startsWith(prefix)) {
                continue;
            }
            String remainder = fileName.substring(prefix.length());
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
