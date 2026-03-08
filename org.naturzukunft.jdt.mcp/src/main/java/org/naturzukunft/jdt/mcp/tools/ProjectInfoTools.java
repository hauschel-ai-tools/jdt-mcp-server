package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;
import org.naturzukunft.jdt.mcp.ProjectImporter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for project information using JDT.
 */
public class ProjectInfoTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Get server version.
     */
    public static ToolRegistration getVersionTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(),
                List.of(),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_version",
                "Get the JDT MCP Server version. Returns the version string (e.g. '0.2.0') or 'development' for local builds.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> {
            Map<String, Object> result = new HashMap<>();
            result.put("version", org.naturzukunft.jdt.mcp.VersionInfo.getVersion());
            result.put("serverName", "Eclipse JDT MCP Server");
            try {
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            } catch (Exception e) {
                return new CallToolResult("Error: " + e.getMessage(), true);
            }
        });
    }

    /**
     * Tool: List all Java projects in workspace.
     */
    public static ToolRegistration listProjectsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(),
                List.of(),
                null, null, null);

        Tool tool = new Tool(
                "jdt_list_projects",
                "🚀 START HERE - ALWAYS CALL THIS FIRST! " +
                "Lists all Java projects in the Eclipse workspace. " +
                "RETURNS: Project names (you NEED these for ALL other jdt_* tools), locations, Java versions. " +
                "WORKFLOW: 1) Call this → 2) Pick a project name → 3) Use it in other tools like jdt_find_type, jdt_get_compilation_errors, etc.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> listProjects());
    }

    private static CallToolResult listProjects() {
        try {
            List<Map<String, Object>> projects = new ArrayList<>();

            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                    IJavaProject javaProject = JavaCore.create(project);
                    Map<String, Object> projectInfo = new HashMap<>();
                    projectInfo.put("name", project.getName());
                    projectInfo.put("location", project.getLocation().toString());
                    projectInfo.put("open", project.isOpen());

                    // Java version
                    String javaVersion = javaProject.getOption(JavaCore.COMPILER_SOURCE, true);
                    projectInfo.put("javaVersion", javaVersion);

                    // Maven multi-module information
                    Map<String, Object> mavenInfo = parseMavenInfo(project);
                    if (mavenInfo != null) {
                        projectInfo.putAll(mavenInfo);
                    }

                    projects.add(projectInfo);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectCount", projects.size());
            result.put("projects", projects);

            if (org.naturzukunft.jdt.mcp.HeadlessApplication.isImporting()) {
                result.put("status", "importing");
                result.put("message", "Project import is still in progress. More projects may appear. Call jdt_list_projects again in a few seconds.");
            } else {
                result.put("status", "ready");
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error listing projects: " + e.getMessage(), true);
        }
    }

    /**
     * Parse Maven pom.xml for multi-module information.
     * Extracts: groupId, artifactId, parent info, and modules list.
     */
    private static Map<String, Object> parseMavenInfo(IProject project) {
        try {
            IFile pomFile = project.getFile("pom.xml");
            if (!pomFile.exists()) {
                return null;
            }

            Map<String, Object> mavenInfo = new HashMap<>();
            String pomContent = new String(pomFile.getContents().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            // Extract groupId (first occurrence, not inside parent)
            java.util.regex.Pattern groupIdPattern = java.util.regex.Pattern.compile(
                "^\\s*<groupId>([^<]+)</groupId>", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher groupIdMatcher = groupIdPattern.matcher(pomContent);
            // Skip if inside <parent> block - look for groupId after </parent> or at project level
            String afterParent = pomContent.replaceFirst("<parent>[\\s\\S]*?</parent>", "");
            groupIdMatcher = groupIdPattern.matcher(afterParent);
            if (groupIdMatcher.find()) {
                mavenInfo.put("mavenGroupId", groupIdMatcher.group(1));
            }

            // Extract artifactId (first occurrence outside parent)
            java.util.regex.Pattern artifactIdPattern = java.util.regex.Pattern.compile(
                "^\\s*<artifactId>([^<]+)</artifactId>", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher artifactIdMatcher = artifactIdPattern.matcher(afterParent);
            if (artifactIdMatcher.find()) {
                mavenInfo.put("mavenArtifactId", artifactIdMatcher.group(1));
            }

            // Extract parent info
            java.util.regex.Pattern parentPattern = java.util.regex.Pattern.compile(
                "<parent>[\\s\\S]*?<groupId>([^<]+)</groupId>[\\s\\S]*?<artifactId>([^<]+)</artifactId>[\\s\\S]*?</parent>");
            java.util.regex.Matcher parentMatcher = parentPattern.matcher(pomContent);
            if (parentMatcher.find()) {
                Map<String, String> parentInfo = new HashMap<>();
                parentInfo.put("groupId", parentMatcher.group(1));
                parentInfo.put("artifactId", parentMatcher.group(2));
                mavenInfo.put("mavenParent", parentInfo);
            }

            // Extract modules (for aggregator/parent projects)
            java.util.regex.Pattern modulesPattern = java.util.regex.Pattern.compile(
                "<modules>([\\s\\S]*?)</modules>");
            java.util.regex.Matcher modulesMatcher = modulesPattern.matcher(pomContent);
            if (modulesMatcher.find()) {
                String modulesContent = modulesMatcher.group(1);
                java.util.regex.Pattern modulePattern = java.util.regex.Pattern.compile(
                    "<module>([^<]+)</module>");
                java.util.regex.Matcher moduleMatcher = modulePattern.matcher(modulesContent);

                List<String> modules = new ArrayList<>();
                while (moduleMatcher.find()) {
                    modules.add(moduleMatcher.group(1));
                }
                if (!modules.isEmpty()) {
                    mavenInfo.put("mavenModules", modules);
                }
            }

            // Check for Maven Wrapper
            IFile mvnw = project.getFile("mvnw");
            mavenInfo.put("hasMavenWrapper", mvnw.exists());

            return mavenInfo.isEmpty() ? null : mavenInfo;

        } catch (Exception e) {
            // Silently ignore errors reading pom.xml
            return null;
        }
    }

    /**
     * Tool: Get project classpath entries.
     */
    public static ToolRegistration getClasspathTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("projectName", Map.of(
                        "type", "string",
                        "description", "Eclipse project name (get from jdt_list_projects, e.g., 'my-app' or 'com.example.myproject')")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_classpath",
                "Get resolved classpath for a Java project. " +
                "Returns source folders (where .java files are), libraries (JARs), and output folders (where .class files go). " +
                "Useful to understand project structure or debug compilation issues.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getClasspath((String) args.get("projectName")));
    }

    private static CallToolResult getClasspath(String projectName) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);

            List<Map<String, Object>> sourceFolders = new ArrayList<>();
            List<Map<String, Object>> libraries = new ArrayList<>();
            List<Map<String, Object>> projects = new ArrayList<>();

            for (IClasspathEntry entry : javaProject.getResolvedClasspath(true)) {
                Map<String, Object> entryInfo = new HashMap<>();
                entryInfo.put("path", entry.getPath().toString());

                switch (entry.getEntryKind()) {
                    case IClasspathEntry.CPE_SOURCE -> {
                        entryInfo.put("outputLocation",
                                entry.getOutputLocation() != null
                                        ? entry.getOutputLocation().toString()
                                        : javaProject.getOutputLocation().toString());
                        sourceFolders.add(entryInfo);
                    }
                    case IClasspathEntry.CPE_LIBRARY -> {
                        entryInfo.put("sourceAttachment",
                                entry.getSourceAttachmentPath() != null
                                        ? entry.getSourceAttachmentPath().toString()
                                        : null);
                        libraries.add(entryInfo);
                    }
                    case IClasspathEntry.CPE_PROJECT -> {
                        projects.add(entryInfo);
                    }
                }
            }

            result.put("sourceFolders", sourceFolders);
            result.put("libraries", libraries);
            result.put("projectDependencies", projects);
            result.put("outputLocation", javaProject.getOutputLocation().toString());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting classpath: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Get compilation errors and warnings.
     */
    public static ToolRegistration getCompilationErrorsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("projectName", Map.of(
                        "type", "string",
                        "description", "Eclipse project name (get from jdt_list_projects)")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_compilation_errors",
                "Get all compilation errors and warnings for a Java project. " +
                "Returns file location, line number, and error message. " +
                "TIP: Call jdt_refresh_project first if you modified files externally, otherwise you may see stale errors.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getCompilationErrors((String) args.get("projectName")));
    }

    private static CallToolResult getCompilationErrors(String projectName) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            IMarker[] markers = project.findMarkers(
                    "org.eclipse.jdt.core.problem",
                    true,
                    IResource.DEPTH_INFINITE);

            List<Map<String, Object>> errors = new ArrayList<>();
            List<Map<String, Object>> warnings = new ArrayList<>();

            for (IMarker marker : markers) {
                Map<String, Object> problem = new HashMap<>();
                problem.put("message", marker.getAttribute(IMarker.MESSAGE, ""));
                problem.put("file", marker.getResource().getLocation().toString());
                problem.put("lineNumber", marker.getAttribute(IMarker.LINE_NUMBER, -1));
                problem.put("charStart", marker.getAttribute(IMarker.CHAR_START, -1));
                problem.put("charEnd", marker.getAttribute(IMarker.CHAR_END, -1));

                int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                if (severity == IMarker.SEVERITY_ERROR) {
                    problem.put("severity", "ERROR");
                    errors.add(problem);
                } else if (severity == IMarker.SEVERITY_WARNING) {
                    problem.put("severity", "WARNING");
                    warnings.add(problem);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("errorCount", errors.size());
            result.put("warningCount", warnings.size());
            result.put("errors", errors);
            result.put("warnings", warnings);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting compilation errors: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Get project structure overview.
     */
    public static ToolRegistration getProjectStructureTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("projectName", Map.of(
                        "type", "string",
                        "description", "Eclipse project name (get from jdt_list_projects)")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_project_structure",
                "Get overview of project structure: Java version, source folders, and all packages. " +
                "Use this to understand the project layout before creating classes or navigating code.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getProjectStructure((String) args.get("projectName")));
    }

    private static CallToolResult getProjectStructure(String projectName) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("location", javaProject.getProject().getLocation().toString());

            // Java version
            result.put("sourceLevel", javaProject.getOption(JavaCore.COMPILER_SOURCE, true));
            result.put("targetLevel", javaProject.getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true));
            result.put("compliance", javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true));

            // Source folders and packages
            List<Map<String, Object>> sourceFolders = new ArrayList<>();
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    Map<String, Object> sourceFolder = new HashMap<>();
                    sourceFolder.put("path", root.getPath().toString());

                    List<String> packages = new ArrayList<>();
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            if (pkg.hasChildren()) {
                                packages.add(pkg.getElementName());
                            }
                        }
                    }
                    sourceFolder.put("packages", packages);
                    sourceFolders.add(sourceFolder);
                }
            }
            result.put("sourceFolders", sourceFolders);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting project structure: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Refresh project or workspace.
     */
    public static ToolRegistration refreshProjectTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("projectName", Map.of(
                        "type", "string",
                        "description", "Eclipse project name to refresh (optional - omit to refresh entire workspace)")),
                List.of(),
                null, null, null);

        Tool tool = new Tool(
                "jdt_refresh_project",
                "⚠️ CRITICAL: Call this AFTER you use Write/Edit tools or git commands on Java files! " +
                "WHY: Eclipse doesn't see filesystem changes automatically. Without refresh, you'll get WRONG results from jdt_parse_java_file, jdt_get_compilation_errors, and refactoring tools. " +
                "WHEN TO CALL: After ANY file modification outside Eclipse → call jdt_refresh_project → then use other JDT tools. " +
                "COMMON MISTAKE: Forgetting to refresh and wondering why jdt_get_compilation_errors still shows old errors.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> refreshProject((String) args.get("projectName")));
    }

    private static CallToolResult refreshProject(String projectName) {
        try {
            Map<String, Object> result = new HashMap<>();

            if (projectName != null && !projectName.isEmpty()) {
                // Refresh specific project
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (project == null || !project.exists()) {
                    return new CallToolResult("Project not found: " + projectName, true);
                }
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                result.put("refreshed", projectName);
                result.put("scope", "project");
            } else {
                // Refresh entire workspace
                ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

                // Also scan for new projects that haven't been imported yet
                String workDir = System.getProperty("user.dir");
                List<IProject> projects = ProjectImporter.importFromPath(
                        Path.of(workDir), new NullProgressMonitor());

                result.put("refreshed", "workspace");
                result.put("scope", "workspace");
                result.put("projectsFound", projects.size());
            }

            result.put("status", "SUCCESS");
            result.put("message", "Refresh completed");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error refreshing: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Update Maven project configuration (re-resolve dependencies).
     */
    public static ToolRegistration mavenUpdateProjectTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("projectName", Map.of(
                        "type", "string",
                        "description", "Eclipse project name (get from jdt_list_projects)")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_maven_update_project",
                "Re-resolve Maven dependencies and update the project classpath. " +
                "Call this after editing pom.xml (adding/removing dependencies, changing versions). " +
                "Equivalent to Eclipse's 'Maven > Update Project'. " +
                "WORKFLOW: Edit pom.xml → jdt_maven_update_project → jdt_refresh_project → jdt_get_compilation_errors",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> mavenUpdateProject((String) args.get("projectName")));
    }

    private static CallToolResult mavenUpdateProject(String projectName) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            IProject project = javaProject.getProject();
            Path projectDir = Path.of(project.getLocation().toOSString());

            if (!java.nio.file.Files.exists(projectDir.resolve("pom.xml"))) {
                return new CallToolResult("Not a Maven project (no pom.xml): " + projectName, true);
            }

            // Resolve new Maven dependencies
            List<IClasspathEntry> mavenEntries = ProjectImporter.resolveMavenDependencies(projectDir);

            // Rebuild classpath: keep source entries and JRE container, replace library entries
            List<IClasspathEntry> newClasspath = new ArrayList<>();
            for (IClasspathEntry entry : javaProject.getRawClasspath()) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                        || entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                        || entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                    newClasspath.add(entry);
                }
            }
            newClasspath.addAll(mavenEntries);

            javaProject.setRawClasspath(
                    newClasspath.toArray(new IClasspathEntry[0]),
                    new NullProgressMonitor());

            // Refresh to pick up any file changes
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("dependenciesResolved", mavenEntries.size());
            result.put("totalClasspathEntries", newClasspath.size());
            result.put("status", "SUCCESS");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error updating Maven project: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Import a project from a directory path.
     */
    public static ToolRegistration importProjectTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("path", Map.of(
                        "type", "string",
                        "description", "Absolute path to the project directory to import (Maven pom.xml, Gradle build.gradle, Eclipse .project, or directory with Java sources)")),
                List.of("path"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_import_project",
                "Import a Java project from a directory into the workspace. " +
                "Supports Maven projects (pom.xml), Gradle projects (build.gradle/build.gradle.kts), " +
                "Eclipse projects (.project), and plain Java projects. " +
                "For Maven multi-module projects, all modules are imported. " +
                "Use this when jdt_list_projects shows 0 projects or to add additional projects.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> importProject((String) args.get("path")));
    }

    private static CallToolResult importProject(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return new CallToolResult("Path is required", true);
            }

            java.nio.file.Path projectPath = Path.of(path);
            if (!java.nio.file.Files.isDirectory(projectPath)) {
                return new CallToolResult("Not a directory: " + path, true);
            }

            List<IProject> imported = ProjectImporter.importFromPath(projectPath, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("importedCount", imported.size());
            result.put("projects", imported.stream()
                    .map(p -> Map.of(
                            "name", p.getName(),
                            "location", p.getLocation().toString()))
                    .toList());
            result.put("status", imported.isEmpty() ? "NO_PROJECTS_FOUND" : "SUCCESS");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error importing project: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Remove a project from the workspace.
     */
    public static ToolRegistration removeProjectTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name to remove (get from jdt_list_projects)"),
                        "deleteContents", Map.of(
                                "type", "boolean",
                                "description", "If true, also delete project files on disk. Default: false (only removes from workspace)")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_remove_project",
                "Remove a project from the Eclipse workspace. " +
                "By default only removes the project reference (files stay on disk). " +
                "Set deleteContents=true to also delete files (DANGEROUS - cannot be undone!).",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> removeProject(
                (String) args.get("projectName"),
                Boolean.TRUE.equals(args.get("deleteContents"))));
    }

    private static CallToolResult removeProject(String projectName, boolean deleteContents) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            String location = project.getLocation().toString();
            project.delete(deleteContents, true, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("location", location);
            result.put("contentsDeleted", deleteContents);
            result.put("status", "SUCCESS");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error removing project: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Reload the entire workspace.
     */
    public static ToolRegistration reloadWorkspaceTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(),
                List.of(),
                null, null, null);

        Tool tool = new Tool(
                "jdt_reload_workspace",
                "Reload the entire workspace: removes all projects, re-imports from the working directory, and rebuilds. " +
                "Use this when the workspace is corrupt, projects are missing, or after major changes to the project structure (e.g. new modules added). " +
                "All other tools are blocked until reload completes. Files on disk are NOT deleted.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> reloadWorkspace(progress));
    }

    private static CallToolResult reloadWorkspace(org.naturzukunft.jdt.mcp.server.ProgressReporter progress) {
        try {
            progress.report(0, 3, "Removing existing projects...");
            java.util.List<IProject> projects = org.naturzukunft.jdt.mcp.HeadlessApplication.reloadWorkspace();
            progress.report(3, 3, "Reload complete");

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("importedCount", projects.size());
            result.put("projects", projects.stream()
                    .map(p -> Map.of(
                            "name", p.getName(),
                            "location", p.getLocation().toString()))
                    .toList());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error reloading workspace: " + e.getMessage(), true);
        }
    }

    /**
     * Helper: Get IJavaProject by name.
     */
    private static IJavaProject getJavaProject(String projectName) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists() && project.isOpen()
                    && project.hasNature(JavaCore.NATURE_ID)) {
                return JavaCore.create(project);
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error getting Java project: " + e.getMessage());
        }
        return null;
    }
}
