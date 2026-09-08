package org.naturzukunft.jdt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link MavenCompilerCompliance}. Pure Java, no Eclipse/OSGi dependency --
 * runs outside the Tycho reactor (see {@code tests/run-unit-tests.sh}), because
 * {@code org.naturzukunft.jdt.mcp} has no Tycho-surefire test fragment set up (#82).
 */
class MavenCompilerComplianceTest {

    @TempDir
    Path dir;

    @Test
    void resolvesFromReleaseProperty() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                  </properties>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("25", result.get().version());
        assertEquals("maven.compiler.release", result.get().propertyKey());
    }

    @Test
    void resolvesFromSourceAndTargetProperties() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <properties>
                    <maven.compiler.source>8</maven.compiler.source>
                    <maven.compiler.target>8</maven.compiler.target>
                  </properties>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("1.8", result.get().version(), "bare major version <=8 maps to JDT's 1.x style");
    }

    @Test
    void resolvesFromCompilerPluginConfiguration() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>17</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("17", result.get().version());
        assertEquals("release", result.get().propertyKey());
    }

    @Test
    void resolvesFromCompilerPluginConfigurationInPluginManagement() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <configuration>
                            <source>21</source>
                            <target>21</target>
                          </configuration>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("21", result.get().version());
    }

    @Test
    void resolvesFromParentPomViaDefaultRelativePath() throws IOException {
        Path parentDir = Files.createDirectory(dir.resolve("parent"));
        writePom(parentDir, """
                <project>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                  </properties>
                </project>
                """);
        Path moduleDir = Files.createDirectory(dir.resolve("parent").resolve("module"));
        Path modulePom = writePom(moduleDir, """
                <project>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(modulePom);

        assertTrue(result.isPresent());
        assertEquals("25", result.get().version());
    }

    @Test
    void resolvesFromParentPomViaExplicitRelativePath() throws IOException {
        Path parentDir = Files.createDirectory(dir.resolve("elsewhere"));
        writePom(parentDir, """
                <project>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """);
        Path moduleDir = Files.createDirectory(dir.resolve("module"));
        Path modulePom = writePom(moduleDir, """
                <project>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <relativePath>../elsewhere/pom.xml</relativePath>
                  </parent>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(modulePom);

        assertTrue(result.isPresent());
        assertEquals("17", result.get().version());
    }

    @Test
    void resolvesPlaceholderReferencingSiblingProperty() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <properties>
                    <java.version>25</java.version>
                    <maven.compiler.release>${java.version}</maven.compiler.release>
                  </properties>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("25", result.get().version());
    }

    @Test
    void returnsEmptyWhenNoCompilerReleaseDeclaredAnywhere() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1.0</version>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isEmpty());
    }

    @Test
    void releasePropertyWinsOverSourceTargetAndPluginConfiguration() throws IOException {
        Path pom = writePom(dir, """
                <project>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>17</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        Optional<MavenCompilerCompliance> result = MavenCompilerCompliance.resolve(pom);

        assertTrue(result.isPresent());
        assertEquals("25", result.get().version());
    }

    private static Path writePom(Path dir, String content) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content);
        return pom;
    }
}
