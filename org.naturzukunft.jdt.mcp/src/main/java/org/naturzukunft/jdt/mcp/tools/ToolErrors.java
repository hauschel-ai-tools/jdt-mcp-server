package org.naturzukunft.jdt.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.naturzukunft.jdt.mcp.McpLogger;
import org.naturzukunft.jdt.mcp.ProjectImporter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Unified error response builder for all MCP tool methods.
 *
 * Ensures consistent JSON error format across all tools:
 * <pre>
 * {
 *   "status": "ERROR",
 *   "message": "Error during <toolName>: <exception message>",
 *   "exceptionType": "IllegalArgumentException",
 *   "cause": "root cause message"  // only if cause exists
 * }
 * </pre>
 *
 * All errors are logged via {@link McpLogger}.
 *
 * @see <a href="https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/32">#32</a>
 */
class ToolErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolErrors() {
        // utility class
    }

    /**
     * Error response for a {@code projectName} the workspace does not know. Lists every open
     * project with its directory, so the caller can see whether the tree it is working in is
     * imported at all -- a git worktree next to the main checkout is not, and its modules must
     * be imported under their own names before anything can be built for it (#126).
     */
    static CallToolResult projectNotFound(String projectName) {
        List<Map<String, Object>> known = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || project.getLocation() == null) {
                continue;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", project.getName());
            entry.put("location", project.getLocation().toString());
            Path importRoot = ProjectImporter.importRootOf(project);
            if (importRoot != null) {
                entry.put("importRoot", importRoot.toString());
            }
            known.add(entry);
        }

        Map<String, Object> error = new HashMap<>();
        error.put("status", "ERROR");
        error.put("message", "Project not found: " + projectName);
        error.put("knownProjects", known);
        error.put("hint", "Pick a name from knownProjects whose location is the directory you are "
                + "working in. If that directory is not listed (for example a git worktree next to "
                + "the main checkout), import it first with jdt_import_project(path=<directory>); "
                + "its modules then appear as '<name>@<directory name>' and build against that tree.");

        try {
            return new CallToolResult(MAPPER.writeValueAsString(error), true);
        } catch (Exception ex) {
            return new CallToolResult("Project not found: " + projectName, true);
        }
    }

    /**
     * Creates a unified error response for exception-based tool failures.
     * Logs the error and returns a JSON-formatted {@link CallToolResult}.
     *
     * @param toolName short tool identifier for logging and message (e.g. "extract method", "rename")
     * @param e the exception that occurred
     * @return a {@link CallToolResult} with isError=true and structured JSON body
     */
    static CallToolResult errorResult(String toolName, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        McpLogger.error(toolName, toolName + " failed: " + msg, e);

        Map<String, Object> error = new HashMap<>();
        error.put("status", "ERROR");
        error.put("message", "Error during " + toolName + ": " + msg);
        error.put("exceptionType", e.getClass().getSimpleName());
        if (e.getCause() != null) {
            error.put("cause", e.getCause().toString());
        }

        try {
            return new CallToolResult(MAPPER.writeValueAsString(error), true);
        } catch (Exception ex) {
            return new CallToolResult("Error during " + toolName + ": " + msg, true);
        }
    }
}
