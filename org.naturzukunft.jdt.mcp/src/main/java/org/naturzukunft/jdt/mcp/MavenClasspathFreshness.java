package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether the Eclipse {@code .classpath} a previous import generated for a Maven module
 * still describes that module's POM.
 *
 * <p>The generated {@code .classpath} is a snapshot of {@code mvn dependency:build-classpath} at
 * import time. It usually is not under version control, survives in the module directory, and is
 * reused verbatim when the project is reopened in a later session -- so a dependency added to the
 * POM in the meantime is missing, and a dependency removed (or a version bumped) leaves an entry
 * pointing at a JAR that no longer exists in the local repository. JDT then reports
 * {@code missing required library} and refuses to build the project (#114).
 *
 * <p>Staleness is decided by file timestamps, not by re-resolving: re-resolving costs one
 * {@code mvn dependency:build-classpath} process per module (tens of seconds each on a workspace
 * with dozens of modules), which is exactly the cost that reopening an existing workspace is
 * supposed to avoid. The whole {@code <parent>} chain is compared, because a version bump in an
 * ancestor's {@code <dependencyManagement>} changes a module's resolved classpath without touching
 * the module's own POM.
 *
 * <p>Timestamps can lie in both directions -- a POM restored from an archive or checked out with a
 * preserved mtime looks older than it is, and any tool that rewrites the POM without changing
 * content looks like a change. The first case is handled by the user (or by
 * {@code jdt_maven_update_project}), the second only costs one re-resolution.
 *
 * <p>This class is pure Java (no Eclipse/OSGi dependency) so it can be unit-tested outside the
 * Tycho reactor.
 */
public final class MavenClasspathFreshness {

    private MavenClasspathFreshness() {
    }

    /**
     * Checks whether the {@code .classpath} in {@code moduleDir} needs to be re-resolved from the
     * module's POM chain.
     *
     * @param moduleDir the module directory (the Eclipse project location)
     * @return a human readable reason when the classpath is stale (for logging), or
     *         {@link Optional#empty()} when it is still current -- including the case where
     *         {@code moduleDir} has no {@code pom.xml} at all, since then there is nothing to
     *         compare against
     */
    public static Optional<String> staleReason(Path moduleDir) {
        Path classpathFile = moduleDir.resolve(".classpath");
        Path modulePom = moduleDir.resolve("pom.xml");

        if (!Files.isRegularFile(modulePom)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(classpathFile)) {
            return Optional.of("no .classpath yet");
        }

        FileTime classpathTime = lastModified(classpathFile).orElse(null);
        if (classpathTime == null) {
            return Optional.of("could not read timestamp of .classpath");
        }

        for (Path pom : pomChain(modulePom)) {
            FileTime pomTime = lastModified(pom).orElse(null);
            if (pomTime != null && pomTime.compareTo(classpathTime) > 0) {
                return Optional.of(pom + " is newer than .classpath");
            }
        }
        return Optional.empty();
    }

    /**
     * Returns {@code modulePom} followed by its ancestor POMs, resolved via {@code <parent>} /
     * {@code <relativePath>} the same way {@link MavenCompilerCompliance} resolves them. Stops at
     * the first ancestor that does not exist on disk (a POM resolved from the repository instead of
     * the checkout carries no local timestamp we could compare).
     */
    static List<Path> pomChain(Path modulePom) {
        List<Path> chain = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Path current = modulePom.toAbsolutePath().normalize();
        while (Files.isRegularFile(current) && visited.add(current)) {
            chain.add(current);
            current = MavenCompilerCompliance.parentPomOf(current).orElse(null);
            if (current == null) {
                break;
            }
        }
        return chain;
    }

    private static Optional<FileTime> lastModified(Path file) {
        try {
            return Optional.of(Files.getLastModifiedTime(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
