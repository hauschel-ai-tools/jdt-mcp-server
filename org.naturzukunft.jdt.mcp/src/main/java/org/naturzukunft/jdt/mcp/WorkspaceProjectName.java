package org.naturzukunft.jdt.mcp;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Chooses the workspace name for a project that is about to be imported from a directory.
 *
 * <p>Eclipse identifies a project by name, and the importer derives that name from the module
 * directory or the {@code <module>} entry. A second checkout of the same repository -- typically
 * a git worktree next to the main checkout -- therefore arrives with names the workspace already
 * knows. Reopening the existing project in that case means every later call builds, tests and
 * refactors the <em>other</em> checkout under the name of the requested one, and reports green
 * for a tree it never looked at (issue #126).
 *
 * <p>The rule: a name is reused only when the existing project already lives in the directory
 * being imported. A name taken by a different directory is disambiguated with the import root's
 * directory name ({@code arknet-core@126} for a worktree at {@code arknet-worktrees/126}), and
 * numbered if even that is taken elsewhere. Pure Java, no Eclipse types: the workspace is passed
 * in as a lookup from project name to location.
 */
public final class WorkspaceProjectName {

    /** Fallback suffix for an import root that has no file name (the file system root). */
    static final String ROOT_SUFFIX = "root";

    /**
     * The chosen name and, when it differs from the wanted one, the location of the project
     * that holds the wanted name.
     *
     * @param name    the workspace project name to use
     * @param takenBy location of the project occupying the wanted name, {@code null} when the
     *                wanted name is used as is
     */
    public record Resolution(String name, Path takenBy) {

        public boolean renamed() {
            return takenBy != null;
        }
    }

    private WorkspaceProjectName() {
        // utility class
    }

    /**
     * Resolves the workspace name for {@code moduleDir}.
     *
     * @param wantedName       the name the importer would use if the workspace were empty
     * @param moduleDir        the directory the project is imported from
     * @param importRoot       the directory the whole import started from; its file name becomes
     *                         the suffix when {@code wantedName} is taken
     * @param existingLocation lookup from project name to the location of the workspace project
     *                         of that name, {@code null} when no such project exists
     */
    public static Resolution resolve(String wantedName, Path moduleDir, Path importRoot,
            Function<String, Path> existingLocation) {
        Path wanted = normalize(moduleDir);

        Path existing = existingLocation.apply(wantedName);
        if (existing == null || normalize(existing).equals(wanted)) {
            return new Resolution(wantedName, null);
        }

        String base = wantedName + "@" + suffixFor(importRoot);
        String candidate = base;
        for (int n = 2; ; n++) {
            Path other = existingLocation.apply(candidate);
            if (other == null || normalize(other).equals(wanted)) {
                return new Resolution(candidate, existing);
            }
            candidate = base + "-" + n;
        }
    }

    private static String suffixFor(Path importRoot) {
        Path fileName = importRoot == null ? null : importRoot.toAbsolutePath().normalize().getFileName();
        if (fileName == null || fileName.toString().isEmpty()) {
            return ROOT_SUFFIX;
        }
        // The characters IWorkspace.validateName rejects on some platform, plus whitespace,
        // which is legal but hostile to a name typed into a tool argument.
        return fileName.toString().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
