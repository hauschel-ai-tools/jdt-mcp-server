package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.AssertionFailedException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
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

    /**
     * Warning emitted when the JDT post-rename analysis had to be skipped (issue #29).
     * The rename itself is applied, only JDT's own "does the result still compile" check
     * is missing, so the caller is told how to make up for it.
     */
    /**
     * JDT reports this error when an override is renamed to the name its (already renamed)
     * declaration now carries. Inside the override completion that "shadowing" is exactly the
     * intended override relationship, so it is not treated as a blocking error there.
     */
    private static final String SHADOWED_BY_RENAMED_DECLARATION = "shadowed by a renamed declaration";

    /** Guard against cycles when overrides are renamed recursively (see completeOverrideRenames). */
    private static final int MAX_OVERRIDE_COMPLETION_DEPTH = 5;

    private static final String POST_RENAME_ANALYSIS_SKIPPED_WARNING =
            "Post-rename validation was skipped: Eclipse JDT's RenameAnalyzeUtil cannot open "
            + "preview working copies in headless mode (AssertionFailedException in "
            + "TextFileChange.releaseDocument). The rename itself was applied normally. "
            + "Verify the result with jdt_get_compilation_errors.";

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
                                "description", "What to rename. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#oldMethod'. FIELD: 'com.example.MyClass#oldField'. PACKAGE: 'com.example.oldpackage'"),
                        "newName", Map.of(
                                "type", "string",
                                "description", "New name. For PACKAGE: fully qualified (e.g., 'com.example.newpackage'). For others: simple name (e.g., 'NewClassName')"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "What type of element: 'CLASS', 'METHOD', 'FIELD', or 'PACKAGE'"),
                        "updateReferences", Map.of(
                                "type", "boolean",
                                "description", "Update all references to the renamed element (default: true)"),
                        "renameSubpackages", Map.of(
                                "type", "boolean",
                                "description", "Only for PACKAGE: also rename sub-packages recursively (default: true). E.g., renaming 'com.old' also renames 'com.old.sub' to 'com.new.sub'"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "If true, only preview changes without applying (default: false)")),
                List.of("elementName", "newName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_rename_element",
                "✏️ RENAME SAFELY: NEVER use find-replace for renaming! This tool renames a class/method/field/package AND updates ALL references everywhere. " +
                "WHY USE THIS: Find-replace breaks code. This tool knows Java semantics - renames correctly even with same-named variables in different scopes. " +
                "EXAMPLE: Rename 'userId' to 'customerId' → updates field, getters, setters, all usages in 50 files automatically. " +
                "PACKAGE RENAME: Renames package, moves files, updates all imports. Use renameSubpackages=true (default) to include sub-packages. " +
                "TIP: preview=true shows exactly what changes before applying. " +
                "GENERIC OVERRIDES: overriding methods of a generic declaration (Processor<T>.process(T) → SimpleProcessor.process(String)) are renamed as well; anything that could not be renamed is listed in 'unrenamedOverrides' with status WARNING — those files then need a manual rename. " +
                "⚠️ SEQUENTIAL ONLY: Do NOT call multiple refactoring tools in parallel — they modify shared workspace state. Call them one at a time.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> renameElement(
                (String) args.get("elementName"),
                (String) args.get("newName"),
                (String) args.get("elementType"),
                args.get("updateReferences") != null ? (Boolean) args.get("updateReferences") : true,
                args.get("renameSubpackages") != null ? (Boolean) args.get("renameSubpackages") : true,
                args.get("preview") != null ? (Boolean) args.get("preview") : false,
                0));
    }

    /**
     * @param overrideDepth recursion depth of the override completion described in
     *                      {@link #completeOverrideRenames}; 0 for a call from the tool.
     */
    private static CallToolResult renameElement(String elementName, String newName, String elementType,
            boolean updateReferences, boolean renameSubpackages, boolean previewOnly, int overrideDepth) {
        try {
            // Find the element
            IJavaElement element = RefactoringSupport.findElement(elementName, elementType);
            if (element == null) {
                return new CallToolResult("Element not found: " + elementName + " (type: " + elementType + ")", true);
            }

            McpLogger.info("RenameRefactoring", "Renaming " + elementType + " '" + elementName
                    + "' -> '" + newName + "' (updateReferences=" + updateReferences
                    + ", preview=" + previewOnly + ")");
            McpLogger.info("RenameRefactoring", "Element: " + element.getClass().getSimpleName()
                    + " in project " + (element.getJavaProject() != null ? element.getJavaProject().getElementName() : "null"));

            // Create rename processor directly (not via Descriptor API).
            // Direct processor usage gives full control in headless mode and avoids
            // the Descriptor abstraction layer which is optimized for UI workflows.
            org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor =
                    createRenameProcessor(element, newName, updateReferences, renameSubpackages);
            if (processor == null) {
                return new CallToolResult(
                        "Unsupported element type for rename: " + element.getClass().getSimpleName(), true);
            }

            McpLogger.info("RenameRefactoring", "Using processor: " + processor.getClass().getSimpleName());

            // Captured before the change is applied: afterwards the element handle no
            // longer resolves, but the override check below still needs these values.
            String oldName = element.getElementName();
            IType methodDeclaringType = element instanceof IMethod m ? m.getDeclaringType() : null;
            int methodParameterCount = element instanceof IMethod m2 ? m2.getNumberOfParameters() : -1;

            org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring refactoring =
                    new org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring(processor);

            // Step 1: checkInitialConditions — validates the element can be renamed
            NullProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            McpLogger.info("RenameRefactoring", "checkInitialConditions: severity="
                    + checkStatus.getSeverity() + " entries=" + checkStatus.getEntries().length);
            for (var entry : checkStatus.getEntries()) {
                McpLogger.debug("RenameRefactoring", "  init: [" + entry.getSeverity() + "] " + entry.getMessage());
            }
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
            // JDT runs a post-rename analysis inside checkFinalConditions that does not work
            // headless (see the AssertionFailedException catch below). When it is skipped,
            // participants are never loaded and the change must be taken from the processor.
            boolean postRenameAnalysisSkipped = false;
            try {
                RefactoringStatus finalStatus = refactoring.checkFinalConditions(monitor);
                McpLogger.info("RenameRefactoring", "checkFinalConditions: severity="
                        + finalStatus.getSeverity() + " entries=" + finalStatus.getEntries().length);
                for (var entry : finalStatus.getEntries()) {
                    McpLogger.debug("RenameRefactoring", "  final: [" + entry.getSeverity() + "] " + entry.getMessage());
                }
                checkStatus.merge(finalStatus);
            } catch (IllegalArgumentException e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                // Log full stack trace to identify the exact source of the IAE
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                McpLogger.warn("RenameRefactoring",
                        "checkFinalConditions threw IAE: " + errorMsg
                        + "\nStack trace:\n" + sw.toString());
                if (element instanceof IPackageFragment) {
                    return new CallToolResult("Package rename failed: " + errorMsg, true);
                }
                return renameViaAst(element, newName, updateReferences, previewOnly);
            } catch (AssertionFailedException e) {
                // Headless-mode defect in Eclipse JDT, not in the rename itself:
                // RenameMethodProcessor.doCheckFinalConditions() first builds the complete
                // change set (createChanges()) and only then runs analyzeRenameChanges(),
                // which opens preview working copies via RenameAnalyzeUtil. Outside the IDE
                // that trips Assert.isTrue() in TextFileChange.releaseDocument().
                // The change set is already complete at that point, so the analysis is
                // dropped and the rename continues — with a warning in the result.
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                McpLogger.warn("RenameRefactoring",
                        "checkFinalConditions threw AssertionFailedException (headless JDT, issue #29) — "
                        + "skipping post-rename analysis and using the processor change set directly."
                        + "\nStack trace:\n" + sw.toString());
                postRenameAnalysisSkipped = true;
            }

            // Filter participant errors (harmless in headless mode — Launch/Breakpoint participants)
            List<String> realErrors = RefactoringSupport.getRealErrors(checkStatus).stream()
                    .filter(msg -> overrideDepth == 0 || msg == null
                            || !msg.contains(SHADOWED_BY_RENAMED_DECLARATION))
                    .toList();

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

            List<String> warnings = new ArrayList<>(RefactoringSupport.getNonParticipantWarnings(checkStatus));
            if (postRenameAnalysisSkipped) {
                warnings.add(POST_RENAME_ANALYSIS_SKIPPED_WARNING);
            }
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            if (previewOnly) {
                Change change = createChange(refactoring, processor, postRenameAnalysisSkipped, monitor);
                result.put("status", "PREVIEW");
                result.put("message", "Preview of rename refactoring");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = createChange(refactoring, processor, postRenameAnalysisSkipped, monitor);
            // Count leaf changes BEFORE perform (perform may clear children)
            int leafChangeCount = RefactoringSupport.countLeafChanges(change);
            Map<String, Object> changeDesc = RefactoringSupport.describeChange(change);

            McpLogger.info("RenameRefactoring", "createChange produced " + leafChangeCount
                    + " leaf changes, change type: " + (change != null ? change.getClass().getSimpleName() : "null"));
            logChangeTree(change, 0);

            // If no changes were produced, fall back to AST-based rename (not for packages)
            if (leafChangeCount == 0) {
                McpLogger.warn("RenameRefactoring",
                        "Processor produced empty change (leafChanges: 0)"
                        + (updateReferences ? " with updateReferences=true" : ""));
                if (element instanceof IPackageFragment) {
                    return new CallToolResult(
                            "Package rename produced no changes. The package may already have the target name.", true);
                }
                McpLogger.info("RenameRefactoring", "Falling back to AST-based rename");
                return renameViaAst(element, newName, updateReferences, previewOnly);
            }

            RefactoringSupport.performChange(change, monitor);
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

            if (methodDeclaringType != null && updateReferences) {
                OverrideCompletion completion = completeOverrideRenames(methodDeclaringType, oldName, newName,
                        methodParameterCount, overrideDepth);
                if (!completion.renamed().isEmpty()) {
                    result.put("overridesRenamedSeparately", completion.renamed());
                }
                if (!completion.leftovers().isEmpty()) {
                    result.put("status", "WARNING");
                    result.put("unrenamedOverrides", completion.leftovers());
                    result.put("message", unrenamedOverridesMessage(oldName, newName, completion.leftovers()));
                }
            }

            if (element.getResource() != null) {
                result.put("file", element.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (AssertionFailedException e) {
            // A bare "assertion failed:" tells a caller nothing — name the known headless
            // defect and the way out instead.
            McpLogger.warn("RenameRefactoring", "Rename hit a JDT headless assertion: " + e);
            return renameErrorResult(elementName, newName, elementType, updateReferences,
                    "Eclipse JDT aborted the rename with an internal assertion in headless mode "
                    + "(known limitation, issue #29: TextFileChange/RenameAnalyzeUtil expect a "
                    + "running IDE workbench). No files were changed by this call. "
                    + "Workaround: rename the declaration and its overrides one by one, or apply "
                    + "the change manually and verify with jdt_get_compilation_errors.",
                    List.of("AssertionFailedException: " + e.getMessage()));
        } catch (Exception e) {
            return ToolErrors.errorResult("rename", e);
        }
    }

    /**
     * Creates the change tree for the rename.
     *
     * When the post-rename analysis was skipped (headless JDT, issue #29), the refactoring's
     * participants were never loaded, so {@code ProcessorBasedRefactoring.createChange()} is
     * not usable and the processor is asked directly. Its change set is complete at that
     * point: {@code RenameMethodProcessor.doCheckFinalConditions()} calls {@code createChanges()}
     * before the analysis step that fails.
     */
    private static Change createChange(
            org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring refactoring,
            org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor,
            boolean postRenameAnalysisSkipped, NullProgressMonitor monitor) throws CoreException {
        if (postRenameAnalysisSkipped) {
            return processor.createChange(monitor);
        }
        return refactoring.createChange(monitor);
    }

    /**
     * Creates the appropriate RenameProcessor for a Java element.
     * Direct processor usage (instead of Descriptor API) gives full control in headless mode.
     */
    private static org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor
            createRenameProcessor(IJavaElement element, String newName, boolean updateReferences,
                    boolean renameSubpackages)
            throws CoreException {
        org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor = null;

        if (element instanceof IPackageFragment pkg) {
            var pkgProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenamePackageProcessor(pkg);
            pkgProcessor.setUpdateReferences(updateReferences);
            pkgProcessor.setRenameSubpackages(renameSubpackages);
            pkgProcessor.setUpdateTextualMatches(false);
            pkgProcessor.setUpdateQualifiedNames(false);
            processor = pkgProcessor;
        } else if (element instanceof IField field) {
            var fieldProcessor = new org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor(field);
            fieldProcessor.setRenameGetter(false);
            fieldProcessor.setRenameSetter(false);
            fieldProcessor.setUpdateReferences(updateReferences);
            fieldProcessor.setUpdateTextualMatches(false);
            processor = fieldProcessor;
        } else if (element instanceof IMethod method) {
            boolean virtual = isVirtualMethod(method);
            McpLogger.info("RenameRefactoring", "Method '" + method.getElementName()
                    + "' in " + method.getDeclaringType().getFullyQualifiedName()
                    + ": isVirtual=" + virtual
                    + ", isInterface=" + method.getDeclaringType().isInterface()
                    + ", flags=0x" + Integer.toHexString(method.getFlags()));
            if (virtual) {
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
     * Result of the override completion: which overrides this tool renamed on its own and
     * which ones still carry the old name afterwards.
     */
    private record OverrideCompletion(List<String> renamed, List<String> leftovers) {
    }

    /**
     * Renames overriding methods that Eclipse JDT left behind (issue #29).
     *
     * JDT's RippleMethodFinder2 relates a virtual method to its overrides through
     * MethodOverrideTester. For a method declared with a type variable — {@code
     * Processor<T>.process(T)} — it does not relate the override that substitutes the
     * variable — {@code SimpleProcessor.process(String)} — so the override keeps the old
     * name and the code no longer compiles. Non-generic virtual methods are unaffected.
     *
     * The type hierarchy itself is intact, so the leftovers are located through it and
     * renamed one by one. Such a single override is no longer virtual once the declaration
     * has been renamed, which is a case JDT handles correctly — including its call sites
     * and self-calls, which a plain declaration patch would miss.
     *
     * @return the overrides renamed here, and those that are still named {@code oldName}
     */
    private static OverrideCompletion completeOverrideRenames(IType declaringType, String oldName,
            String newName, int parameterCount, int overrideDepth) {
        if (overrideDepth >= MAX_OVERRIDE_COMPLETION_DEPTH) {
            McpLogger.warn("RenameRefactoring", "Override completion depth limit reached for " + oldName);
            return new OverrideCompletion(List.of(), List.of());
        }

        List<IMethod> stale = findOverridesNamed(declaringType, oldName, parameterCount);
        if (stale.isEmpty()) {
            return new OverrideCompletion(List.of(), List.of());
        }

        McpLogger.warn("RenameRefactoring", "JDT left " + stale.size()
                + " override(s) named '" + oldName + "' behind (issue #29) — renaming them separately");

        List<String> renamed = new ArrayList<>();
        for (IMethod override : stale) {
            String qualifiedName = override.getDeclaringType().getFullyQualifiedName() + "#" + oldName;
            CallToolResult nested = renameElement(qualifiedName, newName, "METHOD", true, true, false,
                    overrideDepth + 1);
            if (Boolean.TRUE.equals(nested.isError())) {
                McpLogger.warn("RenameRefactoring", "Override rename failed for " + qualifiedName);
                continue;
            }
            renamed.add(qualifiedName);
        }

        List<String> leftovers = findOverridesNamed(declaringType, oldName, parameterCount).stream()
                .map(m -> m.getDeclaringType().getFullyQualifiedName() + "#" + oldName)
                .toList();
        return new OverrideCompletion(renamed, leftovers);
    }

    /**
     * Finds methods named {@code name} with {@code parameterCount} parameters in all subtypes
     * of {@code declaringType}. Overloads are skipped: with several same-named methods in a
     * subtype the override cannot be identified by name alone.
     */
    private static List<IMethod> findOverridesNamed(IType declaringType, String name, int parameterCount) {
        List<IMethod> found = new ArrayList<>();
        try {
            NullProgressMonitor monitor = new NullProgressMonitor();
            for (IType subtype : declaringType.newTypeHierarchy(monitor).getAllSubtypes(declaringType)) {
                ICompilationUnit unit = subtype.getCompilationUnit();
                if (unit == null) {
                    continue;
                }
                unit.makeConsistent(monitor);
                List<IMethod> sameName = java.util.Arrays.stream(subtype.getMethods())
                        .filter(m -> m.getElementName().equals(name))
                        .toList();
                if (sameName.size() == 1 && sameName.get(0).getNumberOfParameters() == parameterCount) {
                    found.add(sameName.get(0));
                }
            }
        } catch (Exception e) {
            McpLogger.warn("RenameRefactoring", "Could not inspect subtypes of "
                    + declaringType.getFullyQualifiedName() + ": " + e);
        }
        return found;
    }

    /**
     * Message for overrides that are still named {@code oldName} — the caller must be able to
     * see from the response alone what is broken and what to do about it.
     */
    private static String unrenamedOverridesMessage(String oldName, String newName, List<String> leftovers) {
        return "Rename applied to the declaration and its callers, but " + leftovers.size()
                + " overriding method(s) still use the old name '" + oldName + "': "
                + String.join(", ", leftovers) + ". This is a known Eclipse JDT limitation with "
                + "generic type parameters (issue #29): the override is not recognised as related to "
                + "the renamed declaration, and renaming it separately failed as well. The affected "
                + "files most likely do not compile now — rename each listed method to '" + newName
                + "' by hand and check the result with jdt_get_compilation_errors.";
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
     * Logs the change tree recursively for debugging.
     */
    private static void logChangeTree(Change change, int depth) {
        if (change == null) return;
        String indent = "  ".repeat(depth);
        String affected = "";
        if (change.getModifiedElement() != null) {
            affected = " [modifiedElement=" + change.getModifiedElement() + "]";
        }
        McpLogger.debug("RenameRefactoring", indent + change.getClass().getSimpleName()
                + ": " + change.getName() + affected);
        if (change instanceof org.eclipse.ltk.core.refactoring.CompositeChange composite) {
            Change[] children = composite.getChildren();
            McpLogger.debug("RenameRefactoring", indent + "  children: " + children.length);
            for (Change child : children) {
                logChangeTree(child, depth + 1);
            }
        }
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
