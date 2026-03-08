package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for code quality analysis and quick fixes using JDT.
 */
public class CodeQualityTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Find unused code (fields, methods, imports).
     */
    public static ToolRegistration findUnusedCodeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Project name from jdt_list_projects"),
                        "scope", Map.of(
                                "type", "string",
                                "description", "What to check: 'ALL', 'IMPORTS', 'FIELDS', 'METHODS', 'PRIVATE_METHODS' (default: ALL)")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_unused_code",
                "FIND CODE TO DELETE! Scans a project for unused imports, private fields, and private methods. " +
                "Helps you clean up the codebase by identifying dead code. " +
                "WORKFLOW: Run this, then use jdt_organize_imports to remove unused imports, " +
                "or manually delete unused fields/methods. Less code = fewer bugs!",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findUnusedCode(
                (String) args.get("projectName"),
                args.get("scope") != null ? (String) args.get("scope") : "ALL"));
    }

    private static CallToolResult findUnusedCode(String projectName, String scope) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                return new CallToolResult("Not a Java project: " + projectName, true);
            }

            List<Map<String, Object>> unusedImports = new ArrayList<>();
            List<Map<String, Object>> unusedFields = new ArrayList<>();
            List<Map<String, Object>> unusedMethods = new ArrayList<>();

            boolean checkImports = "ALL".equalsIgnoreCase(scope) || "IMPORTS".equalsIgnoreCase(scope);
            boolean checkFields = "ALL".equalsIgnoreCase(scope) || "FIELDS".equalsIgnoreCase(scope);
            boolean checkMethods = "ALL".equalsIgnoreCase(scope) || "METHODS".equalsIgnoreCase(scope) ||
                                   "PRIVATE_METHODS".equalsIgnoreCase(scope);

            // Iterate over all compilation units
            for (IPackageFragment pkg : javaProject.getPackageFragments()) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    // Parse for problems
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);
                    CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

                    // Check for unused import warnings
                    if (checkImports) {
                        for (IProblem problem : ast.getProblems()) {
                            if (problem.getID() == IProblem.UnusedImport) {
                                Map<String, Object> info = new HashMap<>();
                                info.put("file", cu.getResource().getLocation().toString());
                                info.put("line", problem.getSourceLineNumber());
                                info.put("message", problem.getMessage());
                                info.put("type", "UNUSED_IMPORT");
                                unusedImports.add(info);
                            }
                        }
                    }

                    // Check for unused private fields
                    if (checkFields) {
                        for (IProblem problem : ast.getProblems()) {
                            if (problem.getID() == IProblem.UnusedPrivateField) {
                                Map<String, Object> info = new HashMap<>();
                                info.put("file", cu.getResource().getLocation().toString());
                                info.put("line", problem.getSourceLineNumber());
                                info.put("message", problem.getMessage());
                                info.put("type", "UNUSED_FIELD");
                                unusedFields.add(info);
                            }
                        }
                    }

                    // Check for unused private methods
                    if (checkMethods) {
                        for (IProblem problem : ast.getProblems()) {
                            if (problem.getID() == IProblem.UnusedPrivateMethod) {
                                Map<String, Object> info = new HashMap<>();
                                info.put("file", cu.getResource().getLocation().toString());
                                info.put("line", problem.getSourceLineNumber());
                                info.put("message", problem.getMessage());
                                info.put("type", "UNUSED_METHOD");
                                unusedMethods.add(info);
                            }
                        }
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("scope", scope);

            result.put("unusedImports", unusedImports);
            result.put("unusedImportCount", unusedImports.size());

            result.put("unusedFields", unusedFields);
            result.put("unusedFieldCount", unusedFields.size());

            result.put("unusedMethods", unusedMethods);
            result.put("unusedMethodCount", unusedMethods.size());

            int totalUnused = unusedImports.size() + unusedFields.size() + unusedMethods.size();
            result.put("totalUnused", totalUnused);

            if (totalUnused == 0) {
                result.put("message", "Great! No unused code found in project.");
            } else {
                result.put("message", "Found " + totalUnused + " unused code elements. " +
                    "Use jdt_organize_imports to remove unused imports. " +
                    "Consider deleting unused fields and methods manually.");
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("find unused code", e);
        }
    }

    /**
     * Tool: Find dead/unreachable code.
     */
    public static ToolRegistration findDeadCodeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Project name from jdt_list_projects")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_dead_code",
                "FIND UNREACHABLE CODE! Scans for code that can never execute: " +
                "statements after return/throw, unreachable catch blocks, dead branches in if/switch. " +
                "This code is useless and should be deleted. Cleaner code = easier maintenance!",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findDeadCode((String) args.get("projectName")));
    }

    private static CallToolResult findDeadCode(String projectName) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                return new CallToolResult("Not a Java project: " + projectName, true);
            }

            List<Map<String, Object>> deadCodeList = new ArrayList<>();

            // Iterate over all compilation units
            for (IPackageFragment pkg : javaProject.getPackageFragments()) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    // Parse for problems
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);
                    CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

                    for (IProblem problem : ast.getProblems()) {
                        // Check for dead code related problems
                        int id = problem.getID();
                        boolean isDeadCode =
                            id == IProblem.CodeCannotBeReached ||
                            id == IProblem.DeadCode ||
                            id == IProblem.UnreachableCatch ||
                            id == IProblem.FallthroughCase;

                        if (isDeadCode) {
                            Map<String, Object> info = new HashMap<>();
                            info.put("file", cu.getResource().getLocation().toString());
                            info.put("line", problem.getSourceLineNumber());
                            info.put("message", problem.getMessage());
                            info.put("offset", problem.getSourceStart());
                            info.put("length", problem.getSourceEnd() - problem.getSourceStart());

                            String type = switch (id) {
                                case IProblem.CodeCannotBeReached -> "UNREACHABLE_CODE";
                                case IProblem.DeadCode -> "DEAD_CODE";
                                case IProblem.UnreachableCatch -> "UNREACHABLE_CATCH";
                                case IProblem.FallthroughCase -> "FALLTHROUGH_CASE";
                                default -> "DEAD_CODE";
                            };
                            info.put("type", type);

                            deadCodeList.add(info);
                        }
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("deadCodeCount", deadCodeList.size());
            result.put("deadCode", deadCodeList);

            if (deadCodeList.isEmpty()) {
                result.put("message", "No dead code found - your code is clean!");
            } else {
                result.put("message", "Found " + deadCodeList.size() + " dead code locations. " +
                    "This code never executes and should be removed.");
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("find dead code", e);
        }
    }
}
