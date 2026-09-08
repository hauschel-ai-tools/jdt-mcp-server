package org.naturzukunft.jdt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkspaceArtifactMatcher} -- the JAR-to-workspace-module matching
 * that keeps reactor siblings from being pulled in twice (issue #116).
 */
class WorkspaceArtifactMatcherTest {

    private static final String REPO = "/home/dev/.m2/repository";

    /** A reactor whose artifactIds are prefixes of one another - the tricky case. */
    private static final Map<String, String> REACTOR = modules(
            "demo-shared-kernel", "org.demo",
            "demo-persistence-support", "org.demo",
            "demo-persistence-test-support", "org.demo");

    private static Map<String, String> modules(String... artifactIdGroupIdPairs) {
        Map<String, String> modules = new LinkedHashMap<>();
        for (int i = 0; i < artifactIdGroupIdPairs.length; i += 2) {
            modules.put(artifactIdGroupIdPairs[i], artifactIdGroupIdPairs[i + 1]);
        }
        return modules;
    }

    private static String repoJar(String groupId, String artifactId, String version) {
        return REPO + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + ".jar";
    }

    @Nested
    @DisplayName("matches by coordinates inside a Maven repository")
    class ByCoordinates {

        @Test
        void snapshotJar() {
            assertEquals("demo-shared-kernel", WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("org.demo", "demo-shared-kernel", "0.9.0-SNAPSHOT"), REACTOR));
        }

        @Test
        void releaseJar() {
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("org.fixture", "fixture-core", "1.0.0"),
                    modules("fixture-api", "org.fixture", "fixture-core", "org.fixture")));
        }

        @Test
        @DisplayName("an artifactId that is a prefix of another does not steal the match")
        void prefixArtifactIdIsNotConfused() {
            assertEquals("demo-persistence-test-support", WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("org.demo", "demo-persistence-test-support", "0.9.0-SNAPSHOT"), REACTOR));
        }

        @Test
        void classifiedJar() {
            String path = REPO + "/org/fixture/fixture-core/1.0.0-SNAPSHOT/"
                    + "fixture-core-1.0.0-SNAPSHOT-tests.jar";
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    path, modules("fixture-core", "org.fixture")));
        }

        @Test
        @DisplayName("groupId is not read when the workspace POM did not yield one")
        void unknownGroupIdSkipsTheCheck() {
            Map<String, String> withoutGroupId = new HashMap<>();
            withoutGroupId.put("fixture-core", null);
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("com.elsewhere", "fixture-core", "1.0.0"), withoutGroupId));
        }

        @Test
        void windowsPathSeparators() {
            String path = "C:\\Users\\dev\\.m2\\repository\\org\\fixture\\fixture-core"
                    + "\\1.0.0\\fixture-core-1.0.0.jar";
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    path, modules("fixture-core", "org.fixture")));
        }
    }

    @Nested
    @DisplayName("rejects foreign artifacts that only share the artifactId")
    class ForeignArtifacts {

        @Test
        @DisplayName("same artifactId, different groupId is not a workspace module")
        void differentGroupId() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("com.elsewhere", "core", "2.5.0"),
                    modules("core", "org.demo")));
        }

        @Test
        @DisplayName("a groupId that merely ends in the same segment is not enough")
        void groupIdIsNotASuffixMatchOnSegments() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("com.notorg.demo.extra", "utils", "1.0"),
                    modules("utils", "org.demo")));
        }

        @Test
        void unrelatedThirdPartyJar() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("org.junit.jupiter", "junit-jupiter", "5.11.4"), REACTOR));
        }

        @Test
        @DisplayName("a repository path naming no workspace module is decided by coordinates alone")
        void repositoryPathNeverFallsBackToNameGuessing() {
            // "demo-shared-kernel-extras" is not a workspace module; the file name alone would
            // not match either, but the point is that the coordinate decision is final
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    repoJar("org.demo", "demo-shared-kernel-extras", "0.9.0"), REACTOR));
        }
    }

    @Nested
    @DisplayName("falls back to the file name outside the repository layout")
    class ByFileName {

        @Test
        void flatDirectoryJar() {
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/fixture-core-1.0.0.jar", modules("fixture-core", "org.fixture")));
        }

        @Test
        @DisplayName("non-numeric version still matches via the prefix fallback")
        void nonNumericVersion() {
            assertEquals("mylib", WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/mylib-RELEASE.jar", modules("mylib", "org.demo")));
        }

        @Test
        @DisplayName("shorter artifactId does not swallow the longer one's JAR")
        void longerArtifactIdWins() {
            assertEquals("foo-bar", WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/foo-bar-1.0.jar", modules("foo", "org.demo", "foo-bar", "org.demo")));
        }

        @Test
        @DisplayName("a JAR of an unknown module is not attributed to its prefix project")
        void unrelatedSiblingIsNotMatched() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/foo-bar-1.0.jar", modules("foo", "org.demo")));
        }

        @Test
        void artifactIdWithoutSeparator() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/fixture-core.jar", modules("fixture-core", "org.fixture")));
        }
    }

    @Nested
    @DisplayName("rejects degenerate input")
    class Degenerate {

        @Test
        void nullAndEmptyInput() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(null, REACTOR));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("", REACTOR));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("/opt/libs/x-1.0.jar", null));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("/opt/libs/x-1.0.jar", Map.of()));
        }

        @Test
        void bareFileNameWithoutDirectories() {
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    "fixture-core-1.0.0.jar", modules("fixture-core", "org.fixture")));
        }

        @Test
        void ignoresEmptyArtifactIds() {
            Map<String, String> withHoles = new LinkedHashMap<>();
            withHoles.put("", "org.demo");
            withHoles.put("fixture-core", "org.fixture");
            assertEquals("fixture-core", WorkspaceArtifactMatcher.matchArtifactId(
                    "/opt/libs/fixture-core-1.0.jar", withHoles));
        }
    }
}
