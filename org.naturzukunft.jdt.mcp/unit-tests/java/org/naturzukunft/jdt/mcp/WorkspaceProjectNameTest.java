package org.naturzukunft.jdt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkspaceProjectName} -- the naming rule that keeps a second checkout
 * of a project (a git worktree next to the main checkout) from silently reopening the
 * workspace project of the first one (issue #126).
 */
class WorkspaceProjectNameTest {

    private static final Path MAIN = Path.of("/home/dev/arknet");
    private static final Path WORKTREE = Path.of("/home/dev/arknet-worktrees/126");

    /** The workspace as the resolver sees it: project name to location. */
    private final Map<String, Path> workspace = new HashMap<>();

    private WorkspaceProjectName.Resolution resolve(String name, Path moduleDir, Path importRoot) {
        return WorkspaceProjectName.resolve(name, moduleDir, importRoot, workspace::get);
    }

    @Test
    @DisplayName("a name nobody uses is taken as is")
    void freshNameIsKept() {
        WorkspaceProjectName.Resolution r = resolve("arknet-core", MAIN.resolve("arknet-core"), MAIN);

        assertEquals("arknet-core", r.name());
        assertFalse(r.renamed());
        assertNull(r.takenBy());
    }

    @Test
    @DisplayName("the same directory imported again reopens the project under its old name")
    void sameLocationReopens() {
        workspace.put("arknet-core", MAIN.resolve("arknet-core"));

        WorkspaceProjectName.Resolution r = resolve("arknet-core", MAIN.resolve("arknet-core"), MAIN);

        assertEquals("arknet-core", r.name());
        assertFalse(r.renamed());
    }

    @Test
    @DisplayName("location comparison ignores redundant path segments")
    void sameLocationIsComparedNormalized() {
        workspace.put("arknet-core", MAIN.resolve("arknet-core"));

        WorkspaceProjectName.Resolution r = resolve("arknet-core",
                Path.of("/home/dev/arknet/./tools/../arknet-core"), MAIN);

        assertFalse(r.renamed());
    }

    @Test
    @DisplayName("a worktree module whose name is taken by another directory gets the worktree's directory name as suffix")
    void collidingNameFromOtherLocationGetsRootSuffix() {
        workspace.put("arknet-core", MAIN.resolve("arknet-core"));

        WorkspaceProjectName.Resolution r = resolve("arknet-core", WORKTREE.resolve("arknet-core"), WORKTREE);

        assertEquals("arknet-core@126", r.name());
        assertTrue(r.renamed());
        assertEquals(MAIN.resolve("arknet-core"), r.takenBy());
    }

    @Test
    @DisplayName("a suffixed project that already points at this directory is reopened, not numbered up")
    void suffixedNameReopensWhenLocationMatches() {
        workspace.put("arknet-core", MAIN.resolve("arknet-core"));
        workspace.put("arknet-core@126", WORKTREE.resolve("arknet-core"));

        WorkspaceProjectName.Resolution r = resolve("arknet-core", WORKTREE.resolve("arknet-core"), WORKTREE);

        assertEquals("arknet-core@126", r.name());
        assertTrue(r.renamed());
    }

    @Test
    @DisplayName("a suffix already taken by a third directory is numbered")
    void suffixCollisionIsNumbered() {
        workspace.put("arknet-core", MAIN.resolve("arknet-core"));
        workspace.put("arknet-core@126", Path.of("/elsewhere/126/arknet-core"));

        WorkspaceProjectName.Resolution r = resolve("arknet-core", WORKTREE.resolve("arknet-core"), WORKTREE);

        assertEquals("arknet-core@126-2", r.name());
        assertEquals(MAIN.resolve("arknet-core"), r.takenBy());
    }

    @Test
    @DisplayName("characters Eclipse rejects in a project name are replaced in the suffix")
    void suffixIsSanitized() {
        workspace.put("app", MAIN.resolve("app"));
        Path root = Path.of("/home/dev/my checkout:v2");

        WorkspaceProjectName.Resolution r = resolve("app", root.resolve("app"), root);

        assertEquals("app@my_checkout_v2", r.name());
    }

    @Test
    @DisplayName("an import root without a file name falls back to a fixed suffix")
    void rootWithoutFileNameGetsFallbackSuffix() {
        workspace.put("app", MAIN.resolve("app"));

        WorkspaceProjectName.Resolution r = resolve("app", Path.of("/app"), Path.of("/"));

        assertEquals("app@root", r.name());
    }
}
