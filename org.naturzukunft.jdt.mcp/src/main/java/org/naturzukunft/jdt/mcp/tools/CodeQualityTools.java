package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.ui.text.java.IInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.swt.widgets.Display;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for code quality analysis and quick fixes using JDT.
 */
@SuppressWarnings("restriction")
public class CodeQualityTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Get and apply quick fixes for compilation errors.
     */
    public static ToolRegistration quickFixTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to the Java file with errors"),
                        "problemOffset", Map.of(
                                "type", "integer",
                                "description", "Position of the error (from jdt_get_compilation_errors). If not provided, fixes all auto-fixable problems."),
                        "fixIndex", Map.of(
                                "type", "integer",
                                "description", "Which fix to apply (0 = first/recommended). Use after previewing to select specific fix."),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "If true, only show available fixes without applying (default: true for safety)")),
                List.of("filePath"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_quick_fix",
                "🔧 AUTO-FIX ERRORS! Got a compilation error? Don't manually edit - let Eclipse fix it! " +
                "FIXES: Missing imports → adds them. Missing method → creates stub. Wrong type → adds cast. Typo → suggests correction. " +
                "SAFE WORKFLOW: 1) preview=true → see options 2) Choose fixIndex → apply. " +
                "EXAMPLE: 'cannot find symbol List' → jdt_quick_fix → adds 'import java.util.List;' automatically! " +
                "MUCH FASTER than manually editing import statements.",
                schema,
                null);

        return new ToolRegistration(tool, args -> quickFix(
                (String) args.get("filePath"),
                args.get("problemOffset") != null ? ((Number) args.get("problemOffset")).intValue() : -1,
                args.get("fixIndex") != null ? ((Number) args.get("fixIndex")).intValue() : 0,
                args.get("preview") != null ? (Boolean) args.get("preview") : true));
    }

    private static CallToolResult quickFix(String filePath, int problemOffset, int fixIndex, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Parse to get problems
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            IProblem[] problems = ast.getProblems();
            if (problems.length == 0) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "NO_PROBLEMS");
                result.put("message", "No compilation problems found - the file is clean!");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Find the problem to fix
            IProblem targetProblem = null;
            if (problemOffset >= 0) {
                for (IProblem p : problems) {
                    if (p.getSourceStart() <= problemOffset && problemOffset <= p.getSourceEnd()) {
                        targetProblem = p;
                        break;
                    }
                }
                if (targetProblem == null) {
                    // Find closest problem
                    int minDist = Integer.MAX_VALUE;
                    for (IProblem p : problems) {
                        int dist = Math.abs(p.getSourceStart() - problemOffset);
                        if (dist < minDist) {
                            minDist = dist;
                            targetProblem = p;
                        }
                    }
                }
            } else {
                // Use first error
                for (IProblem p : problems) {
                    if (p.isError()) {
                        targetProblem = p;
                        break;
                    }
                }
                if (targetProblem == null && problems.length > 0) {
                    targetProblem = problems[0];
                }
            }

            if (targetProblem == null) {
                return new CallToolResult("No problem found at offset " + problemOffset, true);
            }

            // Get quick fixes using JDT internal API
            org.eclipse.jdt.internal.ui.text.correction.AssistContext context =
                new org.eclipse.jdt.internal.ui.text.correction.AssistContext(
                    cu, targetProblem.getSourceStart(),
                    targetProblem.getSourceEnd() - targetProblem.getSourceStart());

            org.eclipse.jdt.internal.ui.text.correction.ProblemLocation problemLocation =
                new org.eclipse.jdt.internal.ui.text.correction.ProblemLocation(targetProblem);

            List<IJavaCompletionProposal> proposals = new ArrayList<>();

            // Collect corrections
            org.eclipse.jdt.internal.ui.text.correction.QuickFixProcessor quickFixProcessor =
                new org.eclipse.jdt.internal.ui.text.correction.QuickFixProcessor();

            IJavaCompletionProposal[] corrections = quickFixProcessor.getCorrections(
                context, new org.eclipse.jdt.internal.ui.text.correction.ProblemLocation[] { problemLocation });

            if (corrections != null) {
                for (IJavaCompletionProposal p : corrections) {
                    proposals.add(p);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("problemMessage", targetProblem.getMessage());
            result.put("problemOffset", targetProblem.getSourceStart());
            result.put("problemLine", targetProblem.getSourceLineNumber());
            result.put("isError", targetProblem.isError());

            if (proposals.isEmpty()) {
                result.put("status", "NO_FIXES");
                result.put("message", "No quick fixes available for this problem");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // List available fixes
            List<Map<String, Object>> fixList = new ArrayList<>();
            for (int i = 0; i < proposals.size(); i++) {
                IJavaCompletionProposal proposal = proposals.get(i);
                Map<String, Object> fix = new HashMap<>();
                fix.put("index", i);
                // Use toString() to get display info
                fix.put("displayString", proposal.toString());
                fix.put("relevance", proposal.getRelevance());
                fixList.add(fix);
            }
            result.put("availableFixes", fixList);
            result.put("fixCount", fixList.size());

            if (previewOnly) {
                result.put("status", "PREVIEW");
                result.put("message", "Available fixes listed above. Call again with preview=false and fixIndex to apply.");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Apply the selected fix
            if (fixIndex < 0 || fixIndex >= proposals.size()) {
                result.put("status", "ERROR");
                result.put("message", "Invalid fixIndex: " + fixIndex + ". Must be 0-" + (proposals.size() - 1));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            IJavaCompletionProposal selectedFix = proposals.get(fixIndex);
            String fixDescription = selectedFix.toString();
            result.put("appliedFix", fixDescription);

            // Apply the fix using CUCorrectionProposal
            if (selectedFix instanceof org.eclipse.jdt.ui.text.java.correction.CUCorrectionProposal cuProposal) {
                // Must run on UI thread to avoid "Invalid thread access" error
                final Exception[] uiException = new Exception[1];
                Display display = Display.getDefault();
                display.syncExec(() -> {
                    try {
                        cuProposal.apply(null); // null document means use internal
                    } catch (Exception e) {
                        uiException[0] = e;
                    }
                });
                if (uiException[0] != null) {
                    throw uiException[0];
                }
                result.put("status", "SUCCESS");
                result.put("message", "Fix applied: " + fixDescription);
            } else {
                // For other proposal types, try to get TextChange
                result.put("status", "ERROR");
                result.put("message", "Cannot apply this fix type automatically. Fix description: " + fixDescription);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error applying quick fix: " + e.getMessage(), true);
        }
    }

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

        return new ToolRegistration(tool, args -> findUnusedCode(
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
            return new CallToolResult("Error finding unused code: " + e.getMessage(), true);
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

        return new ToolRegistration(tool, args -> findDeadCode((String) args.get("projectName")));
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
            return new CallToolResult("Error finding dead code: " + e.getMessage(), true);
        }
    }
}
