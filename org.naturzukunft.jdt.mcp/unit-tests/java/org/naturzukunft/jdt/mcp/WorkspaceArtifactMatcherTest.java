package org.naturzukunft.jdt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkspaceArtifactMatcher} -- the JAR-to-workspace-project matching
 * that keeps reactor siblings from being pulled in twice (issue #116).
 */
class WorkspaceArtifactMatcherTest {

    /** A reactor whose artifactIds are prefixes of one another - the tricky case. */
    private static final List<String> REACTOR = List.of(
            "demo-shared-kernel",
            "demo-persistence-support",
            "demo-persistence-test-support");

    @Nested
    @DisplayName("matches the sibling JAR of a workspace project")
    class Matches {

        @Test
        void snapshotJar() {
            assertEquals("demo-shared-kernel",
                    WorkspaceArtifactMatcher.matchArtifactId("demo-shared-kernel-0.9.0-SNAPSHOT.jar", REACTOR));
        }

        @Test
        void releaseJar() {
            assertEquals("fixture-core",
                    WorkspaceArtifactMatcher.matchArtifactId("fixture-core-1.0.0.jar",
                            Set.of("fixture-api", "fixture-core")));
        }

        @Test
        void classifiedJar() {
            assertEquals("fixture-core",
                    WorkspaceArtifactMatcher.matchArtifactId("fixture-core-1.0.0-SNAPSHOT-tests.jar",
                            Set.of("fixture-core")));
        }

        @Test
        @DisplayName("non-numeric version still matches via the prefix fallback")
        void nonNumericVersion() {
            assertEquals("mylib",
                    WorkspaceArtifactMatcher.matchArtifactId("mylib-RELEASE.jar", Set.of("mylib")));
        }
    }

    @Nested
    @DisplayName("does not confuse artifactIds that are prefixes of each other")
    class PrefixAmbiguity {

        @Test
        void longerArtifactIdWins() {
            assertEquals("demo-persistence-test-support",
                    WorkspaceArtifactMatcher.matchArtifactId(
                            "demo-persistence-test-support-0.9.0-SNAPSHOT.jar", REACTOR));
        }

        @Test
        @DisplayName("shorter artifactId does not swallow the longer one's JAR")
        void shorterArtifactIdIsNotMatched() {
            // "foo-bar-1.0.jar" starts with "foo-", but the remainder "bar-1.0.jar" is no version
            assertEquals("foo-bar",
                    WorkspaceArtifactMatcher.matchArtifactId("foo-bar-1.0.jar", List.of("foo", "foo-bar")));
        }

        @Test
        @DisplayName("a JAR of an unknown sibling module is not attributed to its prefix project")
        void unrelatedSiblingIsNotMatched() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("foo-bar-1.0.jar", List.of("foo")));
        }
    }

    @Nested
    @DisplayName("rejects unrelated and degenerate input")
    class NoMatch {

        @Test
        void thirdPartyJar() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("junit-jupiter-5.11.4.jar", REACTOR));
        }

        @Test
        void artifactIdWithoutSeparator() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("fixture-core.jar", Set.of("fixture-core")));
        }

        @Test
        void nullAndEmptyInput() {
            assertNull(WorkspaceArtifactMatcher.matchArtifactId(null, REACTOR));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("", REACTOR));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("fixture-core-1.0.jar", null));
            assertNull(WorkspaceArtifactMatcher.matchArtifactId("fixture-core-1.0.jar", List.of()));
        }

        @Test
        void ignoresNullAndEmptyArtifactIds() {
            List<String> withHoles = java.util.Arrays.asList(null, "", "fixture-core");
            assertEquals("fixture-core",
                    WorkspaceArtifactMatcher.matchArtifactId("fixture-core-1.0.jar", withHoles));
        }
    }
}
