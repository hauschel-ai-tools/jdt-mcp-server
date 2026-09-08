package org.naturzukunft.jdt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link MavenClasspathFreshness} (issue #114): deciding from a stored fingerprint
 * of the POM chain whether a generated {@code .classpath} still matches the module's POMs.
 */
class MavenClasspathFreshnessTest {

    private static final String MODULE_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>module</artifactId>
            </project>
            """;

    private static final String PARENT_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <modules><module>module</module></modules>
            </project>
            """;

    private static final String CLASSPATH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
              <classpathentry kind="src" path="src/main/java"/>
            </classpath>
            """;

    @Test
    @DisplayName("stored fingerprint still matches the POM chain: current")
    void unchangedChain(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);

        String stamp = MavenClasspathFreshness.fingerprint(moduleDir);

        assertEquals(Optional.empty(), MavenClasspathFreshness.staleReason(moduleDir, stamp));
    }

    @Test
    @DisplayName("a POM touched without a content change stays current — JDT does not rewrite "
            + ".classpath, so the fingerprint must not be anchored to it")
    void pomTouchedWithoutContentChange(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        String stamp = MavenClasspathFreshness.fingerprint(moduleDir);

        // First start after `git checkout`: the mtime moved, so the module is re-resolved once...
        touch(moduleDir.resolve("pom.xml"), Instant.now());
        assertTrue(MavenClasspathFreshness.staleReason(moduleDir, stamp).isPresent(),
                "a moved mtime is a change we cannot tell apart from a real one");

        // ...and the fingerprint taken afterwards settles it, instead of re-triggering forever.
        String afterResolution = MavenClasspathFreshness.fingerprint(moduleDir);
        assertEquals(Optional.empty(), MavenClasspathFreshness.staleReason(moduleDir, afterResolution));
    }

    @Test
    @DisplayName("missing .classpath is stale — nothing was ever resolved")
    void missingClasspath(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        String stamp = MavenClasspathFreshness.fingerprint(moduleDir);
        Files.delete(moduleDir.resolve(".classpath"));

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir, stamp);

        assertTrue(reason.isPresent());
        assertEquals("no .classpath yet", reason.get());
    }

    @Test
    @DisplayName("no stored fingerprint is stale — a workspace from before the stamp existed")
    void noStampStored(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir, null);

        assertTrue(reason.isPresent());
        assertEquals("no record of an earlier classpath resolution", reason.get());
    }

    @Test
    @DisplayName("module POM changed since the stored fingerprint is stale")
    void modulePomChanged(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        String stamp = MavenClasspathFreshness.fingerprint(moduleDir);
        write(moduleDir.resolve("pom.xml"), MODULE_POM.replace("</project>", "  <!-- a dependency -->\n</project>"),
                Instant.now());

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir, stamp);

        assertTrue(reason.isPresent(), "module pom.xml changed, expected stale");
        assertTrue(reason.get().contains("module/pom.xml"), reason.orElse(""));
    }

    @Test
    @DisplayName("ancestor POM changed since the stored fingerprint is stale — "
            + "dependencyManagement lives there")
    void parentPomChanged(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        String stamp = MavenClasspathFreshness.fingerprint(moduleDir);
        write(root.resolve("pom.xml"), PARENT_POM.replace("</project>", "  <!-- version bump -->\n</project>"),
                Instant.now());

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir, stamp);

        assertTrue(reason.isPresent(), "parent pom.xml changed, expected stale");
        assertTrue(reason.get().endsWith("changed since the last classpath resolution"), reason.orElse(""));
    }

    @Test
    @DisplayName("a directory without pom.xml is never stale — there is nothing to compare")
    void notAMavenModule(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("plain"));

        assertEquals(Optional.empty(), MavenClasspathFreshness.staleReason(root.resolve("plain"), null));
    }

    @Test
    @DisplayName("POM chain contains module and ancestor, ancestors resolved via <parent>")
    void pomChainWalksUp(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);

        List<Path> chain = MavenClasspathFreshness.pomChain(moduleDir.resolve("pom.xml"));

        assertEquals(2, chain.size(), chain.toString());
        assertEquals(moduleDir.resolve("pom.xml").toAbsolutePath().normalize(), chain.get(0));
        assertEquals(root.resolve("pom.xml").toAbsolutePath().normalize(), chain.get(1));
    }

    @Test
    @DisplayName("an unrelated pom.xml one directory up is not adopted as parent")
    void foreignPomIsNotTheParent(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        write(root.resolve("pom.xml"), PARENT_POM.replace("<artifactId>parent</artifactId>",
                "<artifactId>somebody-elses-project</artifactId>"), Instant.now());

        List<Path> chain = MavenClasspathFreshness.pomChain(moduleDir.resolve("pom.xml"));

        assertEquals(List.of(moduleDir.resolve("pom.xml").toAbsolutePath().normalize()), chain,
                "the POM above declares a different artifact, it must not enter the chain");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Writes an aggregator POM with one module POM and a generated {@code .classpath} below it, and
     * returns the module directory.
     */
    private static Path layout(Path root) throws IOException {
        Path moduleDir = root.resolve("module");
        Files.createDirectories(moduleDir);
        write(root.resolve("pom.xml"), PARENT_POM, hoursAgo(3));
        write(moduleDir.resolve("pom.xml"), MODULE_POM, hoursAgo(3));
        write(moduleDir.resolve(".classpath"), CLASSPATH, hoursAgo(3));
        return moduleDir;
    }

    private static void write(Path file, String content, Instant modified) throws IOException {
        Files.writeString(file, content);
        touch(file, modified);
    }

    private static void touch(Path file, Instant modified) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minusSeconds(hours * 3600L);
    }
}
