package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Staleness is decided by a <em>fingerprint</em> of the module's POM chain (each POM's
 * modification time and size), which the caller stores next to the project after every resolution
 * and passes back in on the next start. Deliberately not by comparing against the
 * {@code .classpath}'s own timestamp: JDT only writes that file when the classpath actually
 * changes ({@code JavaProject.writeFileEntries} returns early on an unchanged classpath), so a POM
 * that was touched without changing the dependency set -- every {@code git checkout} does this --
 * would re-resolve on every single start, forever.
 *
 * <p>Re-resolving is not free: it is one {@code mvn dependency:build-classpath} process per module,
 * which is exactly the cost that reopening an existing workspace is meant to save. The whole
 * {@code <parent>} chain goes into the fingerprint, because a version bump in an ancestor's
 * {@code <dependencyManagement>} changes a module's resolved classpath without touching the
 * module's own POM. The chain only covers ancestors that exist in the checkout: a parent resolved
 * from the repository instead (no {@code <relativePath>} match, see
 * {@link MavenCompilerCompliance#parentPomOf(Path)}) has no local file to fingerprint, so a change
 * there stays invisible until something local changes too.
 *
 * <p>Modification times can lie -- a POM restored from an archive with a preserved mtime looks
 * unchanged. That case needs {@code jdt_maven_update_project}; the opposite case (a POM rewritten
 * without a content change) costs one re-resolution and then settles, because the new fingerprint
 * is stored.
 *
 * <p>This class is pure Java (no Eclipse/OSGi dependency) so it can be unit-tested outside the
 * Tycho reactor.
 */
public final class MavenClasspathFreshness {

    private MavenClasspathFreshness() {
    }

    /**
     * Checks whether the classpath of the module in {@code moduleDir} needs to be re-resolved.
     *
     * @param moduleDir     the module directory (the Eclipse project location)
     * @param storedStamp   the {@link #fingerprint(Path)} taken when this module's classpath was
     *                      last resolved, or {@code null} when there is no record (a workspace
     *                      from an older version, or a project that was never resolved by us)
     * @return a human readable reason when the classpath must be re-resolved (for logging), or
     *         {@link Optional#empty()} when it is still current -- including the case where
     *         {@code moduleDir} has no {@code pom.xml} at all, since then there is nothing to
     *         compare against
     */
    public static Optional<String> staleReason(Path moduleDir, String storedStamp) {
        Path modulePom = moduleDir.resolve("pom.xml");
        if (!Files.isRegularFile(modulePom)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(moduleDir.resolve(".classpath"))) {
            return Optional.of("no .classpath yet");
        }
        if (storedStamp == null || storedStamp.isBlank()) {
            return Optional.of("no record of an earlier classpath resolution");
        }

        String current = fingerprint(moduleDir);
        if (current.equals(storedStamp)) {
            return Optional.empty();
        }
        return Optional.of(describeDifference(storedStamp, current));
    }

    /**
     * Returns the fingerprint of the module's POM chain: one line per POM, from the module POM up
     * to the last ancestor present in the checkout, each with its modification time and size.
     * Store this after resolving a module's classpath and hand it back to
     * {@link #staleReason(Path, String)} on the next start.
     */
    public static String fingerprint(Path moduleDir) {
        StringBuilder fingerprint = new StringBuilder();
        for (Path pom : pomChain(moduleDir.resolve("pom.xml"))) {
            fingerprint.append(modifiedMillis(pom))
                    .append(':').append(size(pom))
                    .append(':').append(pom)
                    .append('\n');
        }
        return fingerprint.toString();
    }

    /**
     * Returns {@code modulePom} followed by its ancestor POMs, resolved via {@code <parent>} /
     * {@code <relativePath>} the same way {@link MavenCompilerCompliance} resolves them. Stops at
     * the first ancestor that does not exist on disk (a POM resolved from the repository instead of
     * the checkout carries no local timestamp we could compare) and at the first POM already seen
     * (a {@code <relativePath>} pointing back into the chain would otherwise loop).
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

    /** Names the first POM that differs between two fingerprints, for the log. */
    private static String describeDifference(String storedStamp, String current) {
        List<String> stored = storedStamp.lines().toList();
        List<String> now = current.lines().toList();
        for (int i = 0; i < now.size(); i++) {
            if (i >= stored.size() || !stored.get(i).equals(now.get(i))) {
                return pathOf(now.get(i)) + " changed since the last classpath resolution";
            }
        }
        if (now.size() < stored.size()) {
            return pathOf(stored.get(now.size())) + " is gone from the POM chain";
        }
        return "POM chain changed since the last classpath resolution";
    }

    /** Extracts the path from a fingerprint line {@code <millis>:<size>:<path>}. */
    private static String pathOf(String fingerprintLine) {
        int firstColon = fingerprintLine.indexOf(':');
        int secondColon = fingerprintLine.indexOf(':', firstColon + 1);
        return secondColon < 0 ? fingerprintLine : fingerprintLine.substring(secondColon + 1);
    }

    private static long modifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1L;
        }
    }
}
