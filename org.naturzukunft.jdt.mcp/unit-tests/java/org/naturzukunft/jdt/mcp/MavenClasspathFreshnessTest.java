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
 * Unit tests for {@link MavenClasspathFreshness} (issue #114): deciding from file timestamps
 * whether a generated {@code .classpath} still matches the module's POM chain.
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
    @DisplayName("classpath newer than the whole POM chain is current")
    void freshClasspath(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        write(moduleDir.resolve(".classpath"), CLASSPATH, hoursAgo(1));

        assertEquals(Optional.empty(), MavenClasspathFreshness.staleReason(moduleDir));
    }

    @Test
    @DisplayName("missing .classpath is stale — nothing was ever resolved")
    void missingClasspath(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir);

        assertTrue(reason.isPresent());
        assertEquals("no .classpath yet", reason.get());
    }

    @Test
    @DisplayName("module POM newer than .classpath is stale")
    void modulePomTouched(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        write(moduleDir.resolve(".classpath"), CLASSPATH, hoursAgo(2));
        touch(moduleDir.resolve("pom.xml"), Instant.now());

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir);

        assertTrue(reason.isPresent(), "module pom.xml is newer, expected stale");
        assertTrue(reason.get().contains("module/pom.xml"), reason.orElse(""));
    }

    @Test
    @DisplayName("ancestor POM newer than .classpath is stale — dependencyManagement lives there")
    void parentPomTouched(@TempDir Path root) throws IOException {
        Path moduleDir = layout(root);
        write(moduleDir.resolve(".classpath"), CLASSPATH, hoursAgo(2));
        touch(root.resolve("pom.xml"), Instant.now());

        Optional<String> reason = MavenClasspathFreshness.staleReason(moduleDir);

        assertTrue(reason.isPresent(), "parent pom.xml is newer, expected stale");
        assertTrue(reason.get().endsWith("is newer than .classpath"), reason.orElse(""));
    }

    @Test
    @DisplayName("a directory without pom.xml is never stale — there is nothing to compare")
    void notAMavenModule(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("plain"));

        assertEquals(Optional.empty(), MavenClasspathFreshness.staleReason(root.resolve("plain")));
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

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Writes an aggregator POM with one module POM below it and returns the module directory. */
    private static Path layout(Path root) throws IOException {
        Path moduleDir = root.resolve("module");
        Files.createDirectories(moduleDir);
        write(root.resolve("pom.xml"), PARENT_POM, hoursAgo(3));
        write(moduleDir.resolve("pom.xml"), MODULE_POM, hoursAgo(3));
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
