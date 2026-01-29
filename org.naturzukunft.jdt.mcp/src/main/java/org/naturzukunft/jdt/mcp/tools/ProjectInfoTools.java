package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for project information using JDT.
 */
public class ProjectInfoTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: List all Java projects in workspace.
     */
    public static ToolRegistration listProjectsTool() {
        Tool tool = new Tool(
                "jdt_list_projects",
                "List all Java projects in the Eclipse workspace",
                Map.of(
                        "type", "object",
                        "properties", Map.of()));

        return new ToolRegistration(tool, args -> listProjects());
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

                    projects.add(projectInfo);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectCount", projects.size());
            result.put("projects", projects);

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error listing projects: " + e.getMessage());
        }
    }

    /**
     * Tool: Get project classpath entries.
     */
    public static ToolRegistration getClasspathTool() {
        Tool tool = new Tool(
                "jdt_get_classpath",
                "Get resolved classpath for a Java project (source folders, libraries, output folders)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "projectName", Map.of(
                                        "type", "string",
                                        "description", "Name of the Java project")),
                        "required", List.of("projectName")));

        return new ToolRegistration(tool, args -> getClasspath((String) args.get("projectName")));
    }

    private static CallToolResult getClasspath(String projectName) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return errorResult("Java project not found: " + projectName);
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

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error getting classpath: " + e.getMessage());
        }
    }

    /**
     * Tool: Get compilation errors and warnings.
     */
    public static ToolRegistration getCompilationErrorsTool() {
        Tool tool = new Tool(
                "jdt_get_compilation_errors",
                "Get all compilation errors and warnings for a Java project",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "projectName", Map.of(
                                        "type", "string",
                                        "description", "Name of the Java project")),
                        "required", List.of("projectName")));

        return new ToolRegistration(tool, args -> getCompilationErrors((String) args.get("projectName")));
    }

    private static CallToolResult getCompilationErrors(String projectName) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return errorResult("Project not found: " + projectName);
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

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error getting compilation errors: " + e.getMessage());
        }
    }

    /**
     * Tool: Get project structure overview.
     */
    public static ToolRegistration getProjectStructureTool() {
        Tool tool = new Tool(
                "jdt_get_project_structure",
                "Get overview of project structure (Java version, source folders, packages)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "projectName", Map.of(
                                        "type", "string",
                                        "description", "Name of the Java project")),
                        "required", List.of("projectName")));

        return new ToolRegistration(tool, args -> getProjectStructure((String) args.get("projectName")));
    }

    private static CallToolResult getProjectStructure(String projectName) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return errorResult("Java project not found: " + projectName);
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

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error getting project structure: " + e.getMessage());
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

    private static CallToolResult successResult(String content) {
        return new CallToolResult(
                List.of(new McpSchema.TextContent(content)),
                false);
    }

    private static CallToolResult errorResult(String message) {
        return new CallToolResult(
                List.of(new McpSchema.TextContent(message)),
                true);
    }
}
