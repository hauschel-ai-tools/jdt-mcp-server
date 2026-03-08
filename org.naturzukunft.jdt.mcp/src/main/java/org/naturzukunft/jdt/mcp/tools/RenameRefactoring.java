package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;
import org.naturzukunft.jdt.mcp.McpLogger;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tool for renaming Java elements (classes, methods, fields).
 *
 * Extracted from RefactoringTools as part of #30 (God Class refactoring).
 * Contains the processor-based rename path and the AST-based fallback.
 *
 * Note: Uses Eclipse internal refactoring APIs (discouraged access).
 */
@SuppressWarnings("restriction")
class RenameRefactoring {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RenameRefactoring() {
        // utility class
    }

    /**
     * Tool: Rename a Java element (class, method, field).
     */
    static ToolRegistration renameElementTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "What to rename. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#oldMethod'. FIELD: 'com.example.MyClass#oldField'"),
                        "newName", Map.of(
                                "type", "string",
                                "description", "New simple name (e.g., 'NewClassName' or 'newMethodName' - without package)"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "What type of element: 'CLASS', 'METHOD', or 'FIELD'"),
                        "updateReferences", Map.of(
                                "type", "boolean",
                                "description", "Update all references to the renamed element (default: true)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "If true, only preview changes without applying (default: false)")),
                List.of("elementName", "newName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_rename_element",
                "✏️ RENAME SAFELY: NEVER use find-replace for renaming! This tool renames a class/method/field AND updates ALL references everywhere. " +
                "WHY USE THIS: Find-replace breaks code. This tool knows Java semantics - renames correctly even with same-named variables in different scopes. " +
                "EXAMPLE: Rename 'userId' to 'customerId' → updates field, getters, setters, all usages in 50 files automatically. " +
                "TIP: preview=true shows exactly what changes before applying.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> renameElement(
                (String) args.get("elementName"),
                (String) args.get("newName"),
                (String) args.get("elementType"),
                args.get("updateReferences") != null ? (Boolean) args.get("updateReferences") : true,
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult renameElement(String elementName, String newName, String elementType,
            boolean updateReferences, boolean previewOnly) {
        try {
            // Find the element
            IJavaElement element = RefactoringSupport.findElement(elementName, elementType);
            if (element == null) {
                return new CallToolResult("Element not found: " + elementName + " (type: " + elementType + ")", true);
            }

            // Create rename processor directly (not via Descriptor API).
            // Direct processor usage gives full control in headless mode and avoids
            // the Descriptor abstraction layer which is optimized for UI workflows.
            org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor =
                    createRenameProcessor(element, newName, updateReferences);
            if (processor == null) {
                return new CallToolResult(
                        "Unsupported element type for rename: " + element.getClass().getSimpleName(), true);
            }

            org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring refactoring =
                    new org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring(processor);

            // Step 1: checkInitialConditions — validates the element can be renamed
            NullProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            List<String> initErrors = RefactoringSupport.getRealErrors(checkStatus);
            if (!initErrors.isEmpty()) {
                return renameErrorResult(elementName, newName, elementType, updateReferences,
                        "Initial conditions failed: " + String.join("; ", initErrors), initErrors);
            }

            // Step 2: checkFinalConditions — THIS IS WHERE REFERENCES ARE SEARCHED.
            // Without this step, createChange() produces an empty CompositeChange.
            // In headless mode, RenameFieldProcessor may throw IllegalArgumentException
            // for interface fields (public static final in an interface). If that happens,
            // fall back to AST-based rename which works reliably without Participants.
            try {
                RefactoringStatus finalStatus = refactoring.checkFinalConditions(monitor);
                checkStatus.merge(finalStatus);
            } catch (IllegalArgumentException e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                // Log full stack trace to identify the exact source of the IAE
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                McpLogger.warn("RenameRefactoring",
                        "checkFinalConditions threw IAE, falling back to AST-based rename: " + errorMsg
                        + "\nStack trace:\n" + sw.toString());
                return renameViaAst(element, newName, updateReferences, previewOnly);
            }

            // Filter participant errors (harmless in headless mode — Launch/Breakpoint participants)
            List<String> realErrors = RefactoringSupport.getRealErrors(checkStatus);

            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("newName", newName);
            result.put("elementType", elementType);
            result.put("updateReferences", updateReferences);

            if (!realErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Refactoring has errors: " + String.join("; ", realErrors));
                result.put("errors", realErrors);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            List<String> warnings = RefactoringSupport.getNonParticipantWarnings(checkStatus);
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(monitor);
                result.put("status", "PREVIEW");
                result.put("message", "Preview of rename refactoring");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = refactoring.createChange(monitor);
            // Count leaf changes BEFORE perform (perform may clear children)
            int leafChangeCount = RefactoringSupport.countLeafChanges(change);
            Map<String, Object> changeDesc = RefactoringSupport.describeChange(change);

            // If no changes were produced, fall back to AST-based rename
            if (leafChangeCount == 0) {
                McpLogger.warn("RenameRefactoring",
                        "Processor produced empty change (leafChanges: 0)"
                        + (updateReferences ? " with updateReferences=true" : "")
                        + ", falling back to AST-based rename");
                return renameViaAst(element, newName, updateReferences, previewOnly);
            }

            change.perform(monitor);
            result.put("changes", changeDesc);

            // leafChangeCount == 1 means only the declaration was renamed, no references
            if (updateReferences && leafChangeCount <= 1) {
                McpLogger.warn("RenameRefactoring",
                        "Rename completed but leafChanges=" + leafChangeCount
                        + " with updateReferences=true — no references were updated");
                result.put("status", "WARNING");
                result.put("message",
                        "Rename completed but no references were updated (only the declaration was renamed). "
                        + "Consider searching for remaining references manually.");
            } else {
                result.put("status", "SUCCESS");
                result.put("message", "Refactoring completed successfully");
            }

            if (element.getResource() != null) {
                result.put("file", element.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("rename", e);
        }
    }

    /**
     * Creates the appropriate RenameProcessor for a Java element.
     * Direct processor usage (instead of Descriptor API) gives full control in headless mode.
     */
    private static org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor
            createRenameProcessor(IJavaElement element, String newName, boolean updateReferences)
            throws CoreException {
        org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor = null;

        if (element instanceof IField field) {
            var fieldProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor(field);
            fieldProcessor.setRenameGetter(false);
            fieldProcessor.setRenameSetter(false);
            fieldProcessor.setUpdateReferences(updateReferences);
            fieldProcessor.setUpdateTextualMatches(false);
            processor = fieldProcessor;
        } else if (element instanceof IMethod method) {
            if (isVirtualMethod(method)) {
                var virtualProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenameVirtualMethodProcessor(method);
                virtualProcessor.setUpdateReferences(updateReferences);
                processor = virtualProcessor;
            } else {
                var nonVirtualProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor(method);
                nonVirtualProcessor.setUpdateReferences(updateReferences);
                processor = nonVirtualProcessor;
            }
        } else if (element instanceof IType) {
            var typeProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor(element.getJavaProject().findType(((IType) element).getFullyQualifiedName()));
            typeProcessor.setUpdateReferences(updateReferences);
            typeProcessor.setUpdateQualifiedNames(false);
            typeProcessor.setUpdateSimilarDeclarations(false);
            processor = typeProcessor;
        }

        if (processor != null) {
            processor.setNewElementName(newName);
        }
        return processor;
    }

    /**
     * Checks if a method is virtual (interface method, overridden method, etc.).
     * Virtual methods need RenameVirtualMethodProcessor to update all implementations.
     */
    private static boolean isVirtualMethod(IMethod method) {
        try {
            return org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks.isVirtual(method);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * AST-based rename fallback when the Refactoring Processor fails.
     * Uses SearchEngine for reference finding + ASTRewrite for text changes.
     * Works reliably in headless mode without Preferences or Participants.
     * Typical trigger: interface fields where RenameFieldProcessor throws IAE.
     */
    private static CallToolResult renameViaAst(IJavaElement element, String newName,
            boolean updateReferences, boolean previewOnly) {
        try {
            ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu == null) {
                return new CallToolResult("Element has no compilation unit", true);
            }

            String oldName = element.getElementName();
            NullProgressMonitor monitor = new NullProgressMonitor();
            List<Map<String, Object>> changedFiles = new java.util.ArrayList<>();

            // 1. Find all references via SearchEngine (before modifying anything)
            List<org.eclipse.jdt.core.search.SearchMatch> allMatches = new java.util.ArrayList<>();
            if (updateReferences) {
                McpLogger.info("RenameRefactoring", "AST rename: searching references for "
                        + element.getClass().getSimpleName() + " '"
                        + element.getElementName() + "' in "
                        + (element.getJavaProject() != null ? element.getJavaProject().getElementName() : "null"));

                org.eclipse.jdt.core.search.SearchPattern pattern =
                        org.eclipse.jdt.core.search.SearchPattern.createPattern(
                                element,
                                org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES);
                McpLogger.info("RenameRefactoring", "AST rename: search pattern = "
                        + (pattern != null ? pattern.getClass().getSimpleName() + ": " + pattern : "null"));

                if (pattern != null) {
                    org.eclipse.jdt.core.search.IJavaSearchScope scope =
                            org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope();
                    McpLogger.info("RenameRefactoring", "AST rename: workspace scope enclosing projects = "
                            + java.util.Arrays.toString(scope.enclosingProjectsAndJars()));

                    org.eclipse.jdt.core.search.SearchEngine engine =
                            new org.eclipse.jdt.core.search.SearchEngine();
                    List<org.eclipse.jdt.core.search.SearchMatch> allMatchesIncludingPotential = new java.util.ArrayList<>();
                    engine.search(
                            pattern,
                            new org.eclipse.jdt.core.search.SearchParticipant[] {
                                    org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant()
                            },
                            scope,
                            new org.eclipse.jdt.core.search.SearchRequestor() {
                                @Override
                                public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                                    allMatchesIncludingPotential.add(match);
                                    if (match.getAccuracy() == org.eclipse.jdt.core.search.SearchMatch.A_ACCURATE) {
                                        allMatches.add(match);
                                    }
                                }
                            },
                            monitor);
                    McpLogger.info("RenameRefactoring", "AST rename: found " + allMatches.size()
                            + " accurate matches, " + allMatchesIncludingPotential.size() + " total (incl. potential)");
                    for (var m : allMatchesIncludingPotential) {
                        McpLogger.debug("RenameRefactoring", "  match: accuracy="
                                + (m.getAccuracy() == org.eclipse.jdt.core.search.SearchMatch.A_ACCURATE ? "ACCURATE" : "POTENTIAL")
                                + " resource=" + (m.getResource() != null ? m.getResource().getFullPath() : "null")
                                + " offset=" + m.getOffset() + " length=" + m.getLength());
                    }
                }
            }

            // Group matches by CompilationUnit
            Map<ICompilationUnit, List<org.eclipse.jdt.core.search.SearchMatch>> matchesByCU =
                    new java.util.LinkedHashMap<>();
            for (var match : allMatches) {
                Object matchElement = match.getElement();
                if (matchElement instanceof IJavaElement je) {
                    ICompilationUnit matchCU = (ICompilationUnit) je.getAncestor(
                            IJavaElement.COMPILATION_UNIT);
                    if (matchCU != null) {
                        matchesByCU.computeIfAbsent(matchCU, k -> new java.util.ArrayList<>())
                                .add(match);
                    }
                }
            }

            // Preview mode — return what would change without modifying
            if (previewOnly) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "PREVIEW");
                result.put("message", "Preview of AST-based rename");
                result.put("oldName", oldName);
                result.put("newName", newName);
                result.put("fallback", true);

                List<Map<String, Object>> previewChanges = new java.util.ArrayList<>();
                previewChanges.add(Map.of(
                        "file", cu.getResource().getLocation().toString(),
                        "type", "declaration"));
                for (var entry : matchesByCU.entrySet()) {
                    ICompilationUnit refCU = entry.getKey();
                    if (refCU.equals(cu)) continue;
                    previewChanges.add(Map.of(
                            "file", refCU.getResource().getLocation().toString(),
                            "type", "reference",
                            "matchCount", entry.getValue().size()));
                }
                result.put("changedFiles", previewChanges);
                result.put("totalReferences", allMatches.size());
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // 2. Rename the declaration using ASTRewrite
            renameDeclarationViaAst(cu, element, newName, monitor);
            changedFiles.add(Map.of(
                    "file", cu.getResource().getLocation().toString(),
                    "type", "declaration"));

            // 3. Rename references in other compilation units
            for (var entry : matchesByCU.entrySet()) {
                ICompilationUnit refCU = entry.getKey();
                if (refCU.equals(cu)) continue;
                applyRenameEdits(refCU, entry.getValue(), oldName, newName, monitor);
                changedFiles.add(Map.of(
                        "file", refCU.getResource().getLocation().toString(),
                        "type", "reference",
                        "matchCount", entry.getValue().size()));
            }

            // Also handle references in the same CU as the declaration
            List<org.eclipse.jdt.core.search.SearchMatch> sameCUMatches = matchesByCU.get(cu);
            if (sameCUMatches != null && !sameCUMatches.isEmpty()) {
                applyRenameEdits(cu, sameCUMatches, oldName, newName, monitor);
            }

            Map<String, Object> result = new HashMap<>();
            if (updateReferences && allMatches.isEmpty()) {
                result.put("status", "WARNING");
                result.put("message",
                        "Rename applied to declaration only — no references found despite updateReferences=true. "
                        + "Verify that the element has references in the workspace.");
            } else {
                result.put("status", "SUCCESS");
                result.put("message", "Rename completed via AST-based fallback");
            }
            result.put("oldName", oldName);
            result.put("newName", newName);
            result.put("changedFiles", changedFiles);
            result.put("totalReferences", allMatches.size());
            result.put("fallback", true);
            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("AST-based rename", e);
        }
    }

    /**
     * Renames the element declaration in its compilation unit using ASTRewrite.
     */
    private static void renameDeclarationViaAst(ICompilationUnit cu, IJavaElement element,
            String newName, NullProgressMonitor monitor) throws Exception {
        String source = cu.getSource();
        Document doc = new Document(source);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());

        ISourceRange nameRange = ((IMember) element).getNameRange();
        org.eclipse.jdt.core.dom.ASTNode node =
                org.eclipse.jdt.core.dom.NodeFinder.perform(ast, nameRange.getOffset(), nameRange.getLength());

        if (node instanceof org.eclipse.jdt.core.dom.SimpleName simpleName) {
            org.eclipse.jdt.core.dom.SimpleName newNode = ast.getAST().newSimpleName(newName);
            rewrite.replace(simpleName, newNode, null);
        }

        TextEdit edits = rewrite.rewriteAST(doc, cu.getJavaProject().getOptions(true));
        edits.apply(doc);

        cu.getBuffer().setContents(doc.get());
        cu.save(monitor, true);
    }

    /**
     * Applies rename text edits for references found by SearchEngine.
     * Matches are applied back-to-front to keep offsets stable.
     */
    private static void applyRenameEdits(ICompilationUnit cu,
            List<org.eclipse.jdt.core.search.SearchMatch> matches,
            String oldName, String newName, NullProgressMonitor monitor) throws Exception {
        // Sort matches back-to-front so offsets remain stable
        matches.sort((a, b) -> Integer.compare(b.getOffset(), a.getOffset()));

        String source = cu.getSource();
        StringBuilder sb = new StringBuilder(source);

        for (var match : matches) {
            int offset = match.getOffset();
            int length = match.getLength();
            String found = sb.substring(offset, offset + length);
            if (found.equals(oldName)) {
                sb.replace(offset, offset + length, newName);
            }
        }

        cu.getBuffer().setContents(sb.toString());
        cu.save(monitor, true);
    }

    /**
     * Creates an error result for rename operations.
     */
    private static CallToolResult renameErrorResult(String elementName, String newName,
            String elementType, boolean updateReferences, String message, List<String> errors) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("newName", newName);
            result.put("elementType", elementType);
            result.put("updateReferences", updateReferences);
            result.put("status", "ERROR");
            result.put("message", message);
            result.put("errors", errors);
            return new CallToolResult(MAPPER.writeValueAsString(result), true);
        } catch (Exception e) {
            return new CallToolResult("Error during rename: " + message, true);
        }
    }
}
