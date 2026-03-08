package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.naturzukunft.jdt.mcp.McpLogger;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for Java refactoring using JDT.
 *
 * Note: Uses Eclipse internal refactoring APIs (discouraged access).
 * This is necessary to provide actual refactoring functionality.
 */
@SuppressWarnings("restriction")
public class RefactoringTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Rename a Java element (class, method, field).
     */
    public static ToolRegistration renameElementTool() {
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
            IJavaElement element = findElement(elementName, elementType);
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
            List<String> initErrors = getRealErrors(checkStatus);
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
                McpLogger.warn("RefactoringTools",
                        "checkFinalConditions threw IAE, falling back to AST-based rename: " + errorMsg
                        + "\nStack trace:\n" + sw.toString());
                return renameViaAst(element, newName, updateReferences, previewOnly);
            }

            // Filter participant errors (harmless in headless mode — Launch/Breakpoint participants)
            List<String> realErrors = getRealErrors(checkStatus);

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

            List<String> warnings = getNonParticipantWarnings(checkStatus);
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(monitor);
                result.put("status", "PREVIEW");
                result.put("message", "Preview of rename refactoring");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = refactoring.createChange(monitor);
            // Capture change description BEFORE perform (perform may clear children)
            Map<String, Object> changeDesc = describeChange(change);
            int childCount = changeDesc.containsKey("childCount")
                    ? ((Number) changeDesc.get("childCount")).intValue() : -1;

            // If no changes were produced, fall back to AST-based rename
            if (childCount == 0) {
                McpLogger.warn("RefactoringTools",
                        "Processor produced empty change (childCount: 0), falling back to AST-based rename");
                return renameViaAst(element, newName, updateReferences, previewOnly);
            }

            change.perform(monitor);
            result.put("changes", changeDesc);
            result.put("status", "SUCCESS");
            result.put("message", "Refactoring completed successfully");

            if (element.getResource() != null) {
                result.put("file", element.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (e.getCause() != null) {
                msg += " caused by: " + e.getCause();
            }
            error.put("message", "Error during rename: " + msg);
            error.put("exceptionType", e.getClass().getSimpleName());

            StackTraceElement[] stack = e.getStackTrace();
            if (stack.length > 0) {
                int limit = Math.min(stack.length, 5);
                List<String> frames = new java.util.ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    frames.add(stack[i].toString());
                }
                error.put("stackTrace", frames);
            }

            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error during rename: " + msg, true);
            }
        }
    }

    /**
     * Tool: Extract method refactoring.
     */
    public static ToolRegistration extractMethodTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path (e.g., '/home/user/project/src/main/java/com/example/MyClass.java')"),
                        "startOffset", Map.of(
                                "type", "integer",
                                "description", "Start position in characters from file beginning. Get from jdt_parse_java_file (sourceOffset) or jdt_find_references."),
                        "endOffset", Map.of(
                                "type", "integer",
                                "description", "End position in characters. TIP: startOffset + sourceLength from jdt_parse_java_file."),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Name for the new extracted method (e.g., 'calculateTotal')"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Set true to see what will change without applying (default: false)")),
                List.of("filePath", "startOffset", "endOffset", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_extract_method",
                "✂️ REDUCE DUPLICATION: Extract repeated code into a reusable method. " +
                "HOW IT WORKS: Select code range (offset + length) → tool creates new method with correct parameters & return type → replaces original code with method call. " +
                "USE CASE: Method too long? Duplicated logic? Extract it! Clean Code principle: methods should do ONE thing. " +
                "HOW TO GET OFFSETS: jdt_parse_java_file returns sourceOffset for methods - select a range within. " +
                "IMPORTANT: Offsets must align with complete statement boundaries (e.g., start of a statement, end at semicolon). " +
                "Partial expressions or mid-statement selections will fail.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> extractMethod(
                (String) args.get("filePath"),
                ((Number) args.get("startOffset")).intValue(),
                ((Number) args.get("endOffset")).intValue(),
                (String) args.get("methodName"),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult extractMethod(String filePath, int startOffset, int endOffset,
            String methodName, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Get the selected text for validation
            String source = cu.getSource();
            if (startOffset < 0 || endOffset > source.length() || startOffset >= endOffset) {
                return new CallToolResult("Invalid selection range: " + startOffset + "-" + endOffset, true);
            }

            String selectedText = source.substring(startOffset, endOffset);
            int selectionLength = endOffset - startOffset;

            // Use ExtractMethodRefactoring from JDT UI
            // Note: This requires org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring
            // which is internal API

            org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring extractRefactoring =
                new org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring(
                    cu, startOffset, selectionLength);

            extractRefactoring.setMethodName(methodName);
            extractRefactoring.setVisibility(org.eclipse.jdt.core.Flags.AccPrivate);

            // Check preconditions
            RefactoringStatus checkStatus = extractRefactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("startOffset", startOffset);
            result.put("endOffset", endOffset);
            result.put("methodName", methodName);
            result.put("selectedCode", selectedText);
            result.put("lineCount", selectedText.split("\n").length);

            List<String> extractErrors = getRealErrors(checkStatus);
            if (!extractErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Extract method has errors: " + String.join("; ", extractErrors));
                result.put("errors", extractErrors);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (checkStatus.hasWarning()) {
                result.put("warnings", extractStatusMessages(checkStatus));
            }

            if (previewOnly) {
                Change change = extractRefactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of extract method refactoring");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = extractRefactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Extract method completed successfully");
            result.put("changes", describeChange(change));

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error during extract method: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error during extract method: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Move type to another package.
     */
    public static ToolRegistration moveTypeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "typeName", Map.of(
                                "type", "string",
                                "description", "Class to move (e.g., 'com.example.util.Helper'). Get from jdt_find_type."),
                        "targetPackage", Map.of(
                                "type", "string",
                                "description", "Destination package (e.g., 'com.example.common'). Will be created if it doesn't exist."),
                        "updateReferences", Map.of(
                                "type", "boolean",
                                "description", "Update all references to the moved type (default: true)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "If true, only preview changes without applying (default: false)")),
                List.of("typeName", "targetPackage"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_move_type",
                "Move a class/interface/enum to a different package. Updates all imports and references across the workspace. " +
                "Use preview=true first to see impact.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> moveType(
                (String) args.get("typeName"),
                (String) args.get("targetPackage"),
                args.get("updateReferences") != null ? (Boolean) args.get("updateReferences") : true,
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult moveType(String typeName, String targetPackage,
            boolean updateReferences, boolean previewOnly) {
        try {
            // Find the type
            IType type = null;
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(typeName);
                if (type != null) break;
            }

            if (type == null) {
                return new CallToolResult("Type not found: " + typeName, true);
            }

            // Find or create target package
            IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot) type.getPackageFragment().getParent();
            IPackageFragment targetPkg = sourceRoot.getPackageFragment(targetPackage);

            if (targetPkg == null || !targetPkg.exists()) {
                // Create the package
                targetPkg = sourceRoot.createPackageFragment(targetPackage, true, new NullProgressMonitor());
            }

            // Get the refactoring contribution for move
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.MOVE);
            if (contribution == null) {
                return new CallToolResult("Move refactoring not available", true);
            }

            MoveDescriptor descriptor = (MoveDescriptor) contribution.createDescriptor();
            descriptor.setMoveResources(new org.eclipse.core.resources.IFile[0], new org.eclipse.core.resources.IFolder[0],
                    new ICompilationUnit[] { type.getCompilationUnit() });
            descriptor.setDestination(targetPkg);
            descriptor.setUpdateReferences(updateReferences);
            descriptor.setUpdateQualifiedNames(true);

            // Create the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);

            if (refactoring == null) {
                return new CallToolResult("Could not create move refactoring: " + status.toString(), true);
            }

            // Check preconditions
            RefactoringStatus checkStatus = refactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("typeName", typeName);
            result.put("targetPackage", targetPackage);
            result.put("updateReferences", updateReferences);

            List<String> moveErrors = getRealErrors(checkStatus);
            if (!moveErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Move refactoring has errors: " + String.join("; ", moveErrors));
                result.put("errors", moveErrors);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            List<String> moveWarnings = getNonParticipantWarnings(checkStatus);
            if (!moveWarnings.isEmpty()) {
                result.put("warnings", moveWarnings);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of move refactoring");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Move completed successfully");
            result.put("newLocation", targetPackage + "." + type.getElementName());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            McpLogger.error("RefactoringTools", "moveType failed: " + msg, e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error during move: " + msg);
            error.put("exceptionType", e.getClass().getSimpleName());
            if (e.getCause() != null) {
                error.put("cause", e.getCause().toString());
            }
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error during move: " + msg, true);
            }
        }
    }

    /**
     * Tool: Organize imports in a Java file.
     */
    public static ToolRegistration organizeImportsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to .java file (e.g., '/home/user/project/src/main/java/com/example/MyClass.java')"),
                        "removeUnused", Map.of(
                                "type", "boolean",
                                "description", "Remove imports that are not used in the code (default: true)")),
                List.of("filePath"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_organize_imports",
                "Clean up imports in a Java file: removes unused imports and sorts them. " +
                "TIP: Call jdt_refresh_project first if you modified the file externally.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> organizeImports(
                (String) args.get("filePath"),
                args.get("removeUnused") != null ? (Boolean) args.get("removeUnused") : true));
    }

    private static CallToolResult organizeImports(String filePath, boolean removeUnused) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Get imports before
            List<String> importsBefore = new java.util.ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                importsBefore.add(imp.getElementName());
            }

            // Parse the compilation unit
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            // Collect used types and static members from AST
            java.util.Set<String> usedTypes = new java.util.HashSet<>();
            java.util.Set<String> usedStaticMembers = new java.util.HashSet<>();
            ast.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.SimpleName node) {
                    org.eclipse.jdt.core.dom.IBinding binding = node.resolveBinding();
                    if (binding instanceof org.eclipse.jdt.core.dom.ITypeBinding typeBinding) {
                        String qualifiedName = typeBinding.getQualifiedName();
                        if (qualifiedName != null && !qualifiedName.isEmpty() && qualifiedName.contains(".")) {
                            usedTypes.add(qualifiedName);
                        }
                    } else if (binding instanceof org.eclipse.jdt.core.dom.IMethodBinding methodBinding) {
                        // Track static method imports (e.g., assertThat from AssertJ)
                        if ((methodBinding.getModifiers() & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
                            org.eclipse.jdt.core.dom.ITypeBinding declaringClass = methodBinding.getDeclaringClass();
                            if (declaringClass != null) {
                                String staticImport = declaringClass.getQualifiedName() + "." + methodBinding.getName();
                                usedStaticMembers.add(staticImport);
                            }
                        }
                    } else if (binding instanceof org.eclipse.jdt.core.dom.IVariableBinding variableBinding) {
                        // Track static field imports (e.g., constants)
                        if ((variableBinding.getModifiers() & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
                            org.eclipse.jdt.core.dom.ITypeBinding declaringClass = variableBinding.getDeclaringClass();
                            if (declaringClass != null) {
                                String staticImport = declaringClass.getQualifiedName() + "." + variableBinding.getName();
                                usedStaticMembers.add(staticImport);
                            }
                        }
                    }
                    return true;
                }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.QualifiedName node) {
                    if (node.resolveBinding() instanceof org.eclipse.jdt.core.dom.ITypeBinding binding) {
                        String qualifiedName = binding.getQualifiedName();
                        if (qualifiedName != null && !qualifiedName.isEmpty()) {
                            usedTypes.add(qualifiedName);
                        }
                    }
                    return true;
                }
            });

            // Determine which imports to remove
            List<String> toRemove = new java.util.ArrayList<>();
            if (removeUnused) {
                for (IImportDeclaration imp : cu.getImports()) {
                    String importName = imp.getElementName();
                    boolean isUsed = false;
                    boolean isStatic = org.eclipse.jdt.core.Flags.isStatic(imp.getFlags());

                    if (imp.isOnDemand()) {
                        // Star imports - check if any type/member from that package is used
                        String packagePrefix = importName.substring(0, importName.length() - 1); // Remove *
                        if (isStatic) {
                            // Static star import (e.g., import static org.assertj.core.api.Assertions.*)
                            for (String usedMember : usedStaticMembers) {
                                if (usedMember.startsWith(packagePrefix)) {
                                    isUsed = true;
                                    break;
                                }
                            }
                        } else {
                            for (String usedType : usedTypes) {
                                if (usedType.startsWith(packagePrefix)) {
                                    isUsed = true;
                                    break;
                                }
                            }
                        }
                    } else if (isStatic) {
                        // Static import (e.g., import static org.assertj.core.api.Assertions.assertThat)
                        isUsed = usedStaticMembers.contains(importName);
                    } else {
                        // Regular type import
                        isUsed = usedTypes.contains(importName);
                    }

                    if (!isUsed) {
                        toRemove.add(importName);
                    }
                }

                // Remove unused imports
                for (String importToRemove : toRemove) {
                    IImportDeclaration imp = cu.getImport(importToRemove);
                    if (imp != null && imp.exists()) {
                        imp.delete(true, new NullProgressMonitor());
                    }
                }
            }

            // Sort remaining imports (by reorganizing import container)
            cu.getWorkingCopy(null);
            IImportDeclaration[] currentImports = cu.getImports();
            List<String> sortedImports = new java.util.ArrayList<>();
            for (IImportDeclaration imp : currentImports) {
                sortedImports.add(imp.getElementName() + (imp.isOnDemand() ? "" : ""));
            }
            java.util.Collections.sort(sortedImports);

            // Get imports after
            cu.makeConsistent(new NullProgressMonitor());
            List<String> importsAfter = new java.util.ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                importsAfter.add(imp.getElementName());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("filePath", filePath);
            result.put("importsBefore", importsBefore.size());
            result.put("importsAfter", importsAfter.size());
            result.put("removedImports", toRemove);
            result.put("message", "Organize imports completed - removed " + toRemove.size() + " unused imports");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error organizing imports: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error organizing imports: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Inline a local variable or method.
     */
    public static ToolRegistration inlineTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to the Java file"),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Position of the variable/method name to inline (from jdt_parse_java_file)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("filePath", "offset"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_inline",
                "Inline a local variable or method. Replaces all references with the actual value/body. " +
                "Opposite of extract refactoring. Use on a variable/method name position.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> inlineElement(
                (String) args.get("filePath"),
                ((Number) args.get("offset")).intValue(),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult inlineElement(String filePath, int offset, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("offset", offset);

            // Try inline local variable first
            boolean inlineTempFailed = false;
            try {
                org.eclipse.jdt.internal.corext.refactoring.code.InlineTempRefactoring inlineTemp =
                    new org.eclipse.jdt.internal.corext.refactoring.code.InlineTempRefactoring(cu, offset, 0);

                RefactoringStatus status = inlineTemp.checkInitialConditions(new NullProgressMonitor());

                if (!status.hasError()) {
                    RefactoringStatus checkStatus = inlineTemp.checkAllConditions(new NullProgressMonitor());

                    List<String> inlineErrors = getRealErrors(checkStatus);
                    if (!inlineErrors.isEmpty()) {
                        result.put("status", "ERROR");
                        result.put("message", "Inline has errors: " + String.join("; ", inlineErrors));
                        return new CallToolResult(MAPPER.writeValueAsString(result), true);
                    }

                    if (previewOnly) {
                        Change change = inlineTemp.createChange(new NullProgressMonitor());
                        result.put("status", "PREVIEW");
                        result.put("inlineType", "LOCAL_VARIABLE");
                        result.put("changes", describeChange(change));
                        return new CallToolResult(MAPPER.writeValueAsString(result), false);
                    }

                    Change change = inlineTemp.createChange(new NullProgressMonitor());
                    change.perform(new NullProgressMonitor());

                    result.put("status", "SUCCESS");
                    result.put("inlineType", "LOCAL_VARIABLE");
                    result.put("message", "Variable inlined successfully");
                    return new CallToolResult(MAPPER.writeValueAsString(result), false);
                }
                inlineTempFailed = true;
            } catch (Exception tempEx) {
                // JDT internal error (e.g. NPE in SourceAnalyzer for static factory methods)
                inlineTempFailed = true;
            }

            // InlineTemp didn't work — try InlineMethod
            if (inlineTempFailed) {
                try {
                    org.eclipse.jdt.core.dom.ASTParser parser = org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);
                    org.eclipse.jdt.core.dom.CompilationUnit astRoot =
                        (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(new NullProgressMonitor());

                    org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring inlineMethod =
                        org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring.create(
                            cu, astRoot, offset, 0);

                    if (inlineMethod != null) {
                        RefactoringStatus methodStatus = inlineMethod.checkInitialConditions(new NullProgressMonitor());
                        if (!methodStatus.hasError()) {
                            RefactoringStatus checkStatus = inlineMethod.checkAllConditions(new NullProgressMonitor());

                            List<String> inlineErrors = getRealErrors(checkStatus);
                            if (!inlineErrors.isEmpty()) {
                                result.put("status", "ERROR");
                                result.put("message", "Inline method has errors: " + String.join("; ", inlineErrors));
                                return new CallToolResult(MAPPER.writeValueAsString(result), true);
                            }

                            if (previewOnly) {
                                Change change = inlineMethod.createChange(new NullProgressMonitor());
                                result.put("status", "PREVIEW");
                                result.put("inlineType", "METHOD");
                                result.put("changes", describeChange(change));
                                return new CallToolResult(MAPPER.writeValueAsString(result), false);
                            }

                            Change change = inlineMethod.createChange(new NullProgressMonitor());
                            change.perform(new NullProgressMonitor());

                            result.put("status", "SUCCESS");
                            result.put("inlineType", "METHOD");
                            result.put("message", "Method inlined successfully");
                            return new CallToolResult(MAPPER.writeValueAsString(result), false);
                        }
                    }
                } catch (Exception methodEx) {
                    // JDT internal error — NPE in SourceAnalyzer for certain method patterns
                    // (e.g. static factory methods). This is a known JDT limitation in headless mode.
                    String detail = methodEx.getMessage() != null ? methodEx.getMessage() : methodEx.getClass().getSimpleName();
                    result.put("status", "ERROR");
                    result.put("message", "Inline method not supported for this method. " +
                            "JDT cannot inline certain patterns (e.g. static factory methods) in headless mode. " +
                            "Detail: " + detail);
                    return new CallToolResult(MAPPER.writeValueAsString(result), true);
                }
            }

            result.put("status", "ERROR");
            result.put("message", "No inlineable element found at position " + offset + ". Position must be on a local variable assignment or a method name.");
            return new CallToolResult(MAPPER.writeValueAsString(result), true);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            McpLogger.error("RefactoringTools", "inlineElement failed: " + msg, e);
            return new CallToolResult("Error during inline: " + msg, true);
        }
    }

    /**
     * Tool: Extract interface from a class.
     */
    public static ToolRegistration extractInterfaceTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name to extract interface from (e.g., 'com.example.UserServiceImpl')"),
                        "interfaceName", Map.of(
                                "type", "string",
                                "description", "Name for the new interface (e.g., 'UserService')"),
                        "methodNames", Map.of(
                                "type", "array",
                                "description", "List of method names to include in interface. Use '*' for all public methods."),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("className", "interfaceName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_extract_interface",
                "Extract an interface from a class. Select which methods to include. " +
                "The class will implement the new interface. Great for introducing abstraction.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> extractInterface(
                (String) args.get("className"),
                (String) args.get("interfaceName"),
                args.get("methodNames") != null ? (List<String>) args.get("methodNames") : List.of("*"),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult extractInterface(String className, String interfaceName,
            List<String> methodNames, boolean previewOnly) {
        try {
            IType type = null;
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(className);
                if (type != null) break;
            }

            if (type == null) {
                return new CallToolResult("Class not found: " + className, true);
            }

            // Collect methods to extract
            List<IMethod> methodsToExtract = new java.util.ArrayList<>();
            boolean includeAll = methodNames.contains("*");

            for (IMethod method : type.getMethods()) {
                if (includeAll || methodNames.contains(method.getElementName())) {
                    // Only include public non-static methods
                    int flags = method.getFlags();
                    if (org.eclipse.jdt.core.Flags.isPublic(flags) &&
                        !org.eclipse.jdt.core.Flags.isStatic(flags) &&
                        !method.isConstructor()) {
                        methodsToExtract.add(method);
                    }
                }
            }

            if (methodsToExtract.isEmpty()) {
                return new CallToolResult("No public methods found to extract", true);
            }

            // Build interface source manually since the internal API has changed
            StringBuilder interfaceSource = new StringBuilder();
            String packageName = type.getPackageFragment().getElementName();

            if (!packageName.isEmpty()) {
                interfaceSource.append("package ").append(packageName).append(";\n\n");
            }

            // Collect imports needed
            java.util.Set<String> imports = new java.util.TreeSet<>();
            for (IMethod method : methodsToExtract) {
                String returnType = method.getReturnType();
                // Add imports for parameter types and return types if needed
                for (String paramType : method.getParameterTypes()) {
                    addImportIfNeeded(paramType, imports);
                }
                addImportIfNeeded(returnType, imports);
            }

            for (String imp : imports) {
                interfaceSource.append("import ").append(imp).append(";\n");
            }
            if (!imports.isEmpty()) {
                interfaceSource.append("\n");
            }

            interfaceSource.append("public interface ").append(interfaceName).append(" {\n\n");

            for (IMethod method : methodsToExtract) {
                interfaceSource.append("    ");
                interfaceSource.append(org.eclipse.jdt.core.Signature.toString(method.getReturnType()));
                interfaceSource.append(" ").append(method.getElementName()).append("(");

                String[] paramNames = method.getParameterNames();
                String[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramNames.length; i++) {
                    if (i > 0) interfaceSource.append(", ");
                    interfaceSource.append(org.eclipse.jdt.core.Signature.toString(paramTypes[i]));
                    interfaceSource.append(" ").append(paramNames[i]);
                }

                interfaceSource.append(")");

                String[] exceptions = method.getExceptionTypes();
                if (exceptions.length > 0) {
                    interfaceSource.append(" throws ");
                    for (int i = 0; i < exceptions.length; i++) {
                        if (i > 0) interfaceSource.append(", ");
                        interfaceSource.append(org.eclipse.jdt.core.Signature.toString(exceptions[i]));
                    }
                }

                interfaceSource.append(";\n\n");
            }

            interfaceSource.append("}\n");

            // Prepare result
            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("interfaceName", interfaceName);
            result.put("methodCount", methodsToExtract.size());
            result.put("methods", methodsToExtract.stream().map(IMethod::getElementName).toList());
            result.put("newInterface", packageName.isEmpty() ? interfaceName : packageName + "." + interfaceName);

            // Prepare class modification
            ICompilationUnit classCu = type.getCompilationUnit();
            String classSource = classCu.getSource();
            String newClassSource = null;

            // Find class declaration and prepare "implements InterfaceName"
            String classDecl = "class " + type.getElementName();
            int classPos = classSource.indexOf(classDecl);
            if (classPos >= 0) {
                int afterClass = classPos + classDecl.length();
                String beforeBrace = classSource.substring(afterClass, classSource.indexOf('{', afterClass));

                String newImplements;
                if (beforeBrace.contains("implements")) {
                    // Already has implements, add to list
                    newImplements = " " + beforeBrace.trim().replace("implements", "implements " + interfaceName + ",");
                } else if (beforeBrace.contains("extends")) {
                    // Has extends but no implements
                    newImplements = " " + beforeBrace.trim() + " implements " + interfaceName;
                } else {
                    // No extends or implements
                    newImplements = " implements " + interfaceName;
                }

                newClassSource = classSource.substring(0, afterClass) + newImplements + " " +
                    classSource.substring(classSource.indexOf('{', afterClass));
            }

            // Preview mode: return what would be changed without applying
            if (previewOnly) {
                result.put("status", "PREVIEW");
                result.put("message", "Preview of interface extraction (no changes applied)");

                List<Map<String, Object>> changes = new java.util.ArrayList<>();

                // New interface file
                Map<String, Object> newFileChange = new HashMap<>();
                newFileChange.put("type", "CREATE_FILE");
                newFileChange.put("file", type.getPackageFragment().getResource().getLocation().toString()
                    + "/" + interfaceName + ".java");
                newFileChange.put("content", interfaceSource.toString());
                changes.add(newFileChange);

                // Class modification
                if (newClassSource != null) {
                    Map<String, Object> classChange = new HashMap<>();
                    classChange.put("type", "MODIFY_FILE");
                    classChange.put("file", classCu.getResource().getLocation().toString());
                    classChange.put("description", "Add 'implements " + interfaceName + "' to class declaration");
                    changes.add(classChange);
                }

                result.put("changes", changes);
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Apply changes: Create the interface file
            IPackageFragment pkg = type.getPackageFragment();
            ICompilationUnit newInterface = pkg.createCompilationUnit(
                interfaceName + ".java",
                interfaceSource.toString(),
                true,
                new NullProgressMonitor());

            // Apply changes: Update the class to implement the interface
            if (newClassSource != null) {
                ICompilationUnit workingCopy = classCu.getWorkingCopy(new NullProgressMonitor());
                try {
                    workingCopy.getBuffer().setContents(newClassSource);
                    workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
                } finally {
                    workingCopy.discardWorkingCopy();
                }
            }

            result.put("status", "SUCCESS");
            result.put("message", "Interface extracted successfully");
            result.put("interfaceFile", newInterface.getResource().getLocation().toString());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error extracting interface: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Change method signature.
     */
    public static ToolRegistration changeMethodSignatureTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.UserService')"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Method name to modify"),
                        "newName", Map.of(
                                "type", "string",
                                "description", "New method name (optional, keep same if not changing)"),
                        "newReturnType", Map.of(
                                "type", "string",
                                "description", "New return type (optional, e.g., 'List<User>')"),
                        "addParameters", Map.of(
                                "type", "array",
                                "description", "Parameters to add: [{\"type\": \"String\", \"name\": \"filter\", \"defaultValue\": \"null\"}]"),
                        "removeParameters", Map.of(
                                "type", "array",
                                "description", "Parameter names to remove"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("className", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_change_method_signature",
                "Change a method's signature: rename, change return type, add/remove parameters. " +
                "All callers are automatically updated. Use preview=true first!",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> changeMethodSignature(
                (String) args.get("className"),
                (String) args.get("methodName"),
                (String) args.get("newName"),
                (String) args.get("newReturnType"),
                (List<Map<String, String>>) args.get("addParameters"),
                (List<String>) args.get("removeParameters"),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult changeMethodSignature(String className, String methodName,
            String newName, String newReturnType, List<Map<String, String>> addParameters,
            List<String> removeParameters, boolean previewOnly) {
        try {
            IType type = null;
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(className);
                if (type != null) break;
            }

            if (type == null) {
                return new CallToolResult("Class not found: " + className, true);
            }

            IMethod method = null;
            for (IMethod m : type.getMethods()) {
                if (m.getElementName().equals(methodName)) {
                    method = m;
                    break;
                }
            }

            if (method == null) {
                return new CallToolResult("Method not found: " + methodName + " in " + className, true);
            }

            // Create Change Method Signature refactoring
            org.eclipse.jdt.internal.corext.refactoring.structure.ChangeSignatureProcessor processor =
                new org.eclipse.jdt.internal.corext.refactoring.structure.ChangeSignatureProcessor(method);

            if (newName != null && !newName.isEmpty() && !newName.equals(methodName)) {
                processor.setNewMethodName(newName);
            }

            if (newReturnType != null && !newReturnType.isEmpty()) {
                processor.setNewReturnTypeName(newReturnType);
            }

            // Handle parameter changes
            List<org.eclipse.jdt.internal.corext.refactoring.ParameterInfo> parameterInfos =
                processor.getParameterInfos();

            // Remove parameters
            if (removeParameters != null) {
                for (String paramToRemove : removeParameters) {
                    for (org.eclipse.jdt.internal.corext.refactoring.ParameterInfo info : parameterInfos) {
                        if (info.getOldName().equals(paramToRemove)) {
                            info.markAsDeleted();
                        }
                    }
                }
            }

            // Add parameters
            if (addParameters != null) {
                for (Map<String, String> param : addParameters) {
                    String paramType = param.get("type");
                    String paramName = param.get("name");
                    String defaultValue = param.getOrDefault("defaultValue", "null");

                    org.eclipse.jdt.internal.corext.refactoring.ParameterInfo newParam =
                        org.eclipse.jdt.internal.corext.refactoring.ParameterInfo.createInfoForAddedParameter(
                            paramType, paramName, defaultValue);
                    parameterInfos.add(newParam);
                }
            }

            org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring refactoring =
                new org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring(processor);

            RefactoringStatus status = refactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("methodName", methodName);
            result.put("newName", newName != null ? newName : methodName);

            List<String> realErrors = getRealErrors(status);
            if (!realErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Change signature has errors: " + String.join("; ", realErrors));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Method signature changed successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            McpLogger.error("RefactoringTools", "changeMethodSignature failed: " + msg, e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error changing method signature: " + msg);
            error.put("exceptionType", e.getClass().getSimpleName());
            if (e.getCause() != null) {
                error.put("cause", e.getCause().toString());
            }
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error changing method signature: " + msg, true);
            }
        }
    }

    /**
     * Tool: Convert anonymous class to lambda.
     */
    public static ToolRegistration convertToLambdaTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to the Java file"),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Position inside the anonymous class (from jdt_parse_java_file)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("filePath", "offset"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_convert_to_lambda",
                "Convert an anonymous inner class to a lambda expression. " +
                "Works for single-method interfaces (functional interfaces). " +
                "IMPORTANT: The offset must be INSIDE the anonymous class body (e.g., inside the method implementation), " +
                "NOT at the 'new' keyword. Use jdt_parse_java_file to find the correct offset within the anonymous class.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> convertToLambda(
                (String) args.get("filePath"),
                ((Number) args.get("offset")).intValue(),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult convertToLambda(String filePath, int offset, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Parse AST
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            // Find anonymous class at offset
            org.eclipse.jdt.core.dom.ASTNode node = org.eclipse.jdt.core.dom.NodeFinder.perform(ast, offset, 0);

            // Navigate up to find AnonymousClassDeclaration
            org.eclipse.jdt.core.dom.AnonymousClassDeclaration anonClass = null;
            while (node != null) {
                if (node instanceof org.eclipse.jdt.core.dom.AnonymousClassDeclaration acd) {
                    anonClass = acd;
                    break;
                }
                node = node.getParent();
            }

            if (anonClass == null) {
                return new CallToolResult("No anonymous class found at position " + offset, true);
            }

            // Check if it's a functional interface (single abstract method)
            @SuppressWarnings("unchecked")
            List<org.eclipse.jdt.core.dom.BodyDeclaration> bodyDecls = anonClass.bodyDeclarations();
            int methodCount = 0;
            org.eclipse.jdt.core.dom.MethodDeclaration singleMethod = null;

            for (org.eclipse.jdt.core.dom.BodyDeclaration decl : bodyDecls) {
                if (decl instanceof org.eclipse.jdt.core.dom.MethodDeclaration md) {
                    methodCount++;
                    singleMethod = md;
                }
            }

            if (methodCount != 1) {
                return new CallToolResult("Cannot convert - anonymous class must have exactly one method (found " + methodCount + ")", true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("offset", offset);
            result.put("methodName", singleMethod.getName().getIdentifier());

            // Get the ClassInstanceCreation parent
            org.eclipse.jdt.core.dom.ASTNode parent = anonClass.getParent();
            if (!(parent instanceof org.eclipse.jdt.core.dom.ClassInstanceCreation)) {
                return new CallToolResult("Unexpected AST structure", true);
            }

            // Build lambda expression manually
            StringBuilder lambda = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<org.eclipse.jdt.core.dom.SingleVariableDeclaration> params = singleMethod.parameters();

            if (params.isEmpty()) {
                lambda.append("()");
            } else if (params.size() == 1) {
                lambda.append(params.get(0).getName().getIdentifier());
            } else {
                lambda.append("(");
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) lambda.append(", ");
                    lambda.append(params.get(i).getName().getIdentifier());
                }
                lambda.append(")");
            }

            lambda.append(" -> ");

            // Get body
            org.eclipse.jdt.core.dom.Block body = singleMethod.getBody();
            if (body != null) {
                @SuppressWarnings("unchecked")
                List<org.eclipse.jdt.core.dom.Statement> statements = body.statements();
                if (statements.size() == 1) {
                    org.eclipse.jdt.core.dom.Statement stmt = statements.get(0);
                    if (stmt instanceof org.eclipse.jdt.core.dom.ReturnStatement ret && ret.getExpression() != null) {
                        lambda.append(ret.getExpression().toString());
                    } else if (stmt instanceof org.eclipse.jdt.core.dom.ExpressionStatement expr) {
                        lambda.append(expr.getExpression().toString());
                    } else {
                        lambda.append(body.toString());
                    }
                } else {
                    lambda.append(body.toString());
                }
            }

            result.put("lambdaExpression", lambda.toString());

            if (previewOnly) {
                result.put("status", "PREVIEW");
                result.put("message", "Suggested lambda expression (manual replacement needed)");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // For actual conversion, replace the source
            String source = cu.getSource();
            int start = parent.getStartPosition();
            int end = start + parent.getLength();

            String newSource = source.substring(0, start) + lambda.toString() + source.substring(end);

            ICompilationUnit workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
            try {
                workingCopy.getBuffer().setContents(newSource);
                workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
            } finally {
                workingCopy.discardWorkingCopy();
            }

            result.put("status", "SUCCESS");
            result.put("message", "Converted to lambda expression");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error converting to lambda: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Encapsulate field (make private + getters/setters).
     */
    public static ToolRegistration encapsulateFieldTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.User')"),
                        "fieldName", Map.of(
                                "type", "string",
                                "description", "Field name to encapsulate (e.g., 'name')"),
                        "generateGetter", Map.of(
                                "type", "boolean",
                                "description", "Generate getter method (default: true)"),
                        "generateSetter", Map.of(
                                "type", "boolean",
                                "description", "Generate setter method (default: true)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("className", "fieldName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_encapsulate_field",
                "Encapsulate a field: make it private and generate getter/setter methods. " +
                "Updates all direct field accesses to use the accessors. Best practice for data hiding.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> encapsulateField(
                (String) args.get("className"),
                (String) args.get("fieldName"),
                args.get("generateGetter") != null ? (Boolean) args.get("generateGetter") : true,
                args.get("generateSetter") != null ? (Boolean) args.get("generateSetter") : true,
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult encapsulateField(String className, String fieldName,
            boolean generateGetter, boolean generateSetter, boolean previewOnly) {
        try {
            IType type = null;
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(className);
                if (type != null) break;
            }

            if (type == null) {
                return new CallToolResult("Class not found: " + className, true);
            }

            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return new CallToolResult("Field not found: " + fieldName + " in " + className, true);
            }

            // Create Self Encapsulate Field refactoring
            org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring refactoring =
                new org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring(field);

            refactoring.setGenerateJavadoc(true);
            refactoring.setVisibility(org.eclipse.jdt.core.Flags.AccPrivate);
            refactoring.setEncapsulateDeclaringClass(true);

            // Set getter/setter names
            String capitalizedName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

            String fieldType = field.getTypeSignature();
            boolean isBoolean = "Z".equals(fieldType) || "Qboolean;".equals(fieldType);

            String getterName = (isBoolean ? "is" : "get") + capitalizedName;
            String setterName = "set" + capitalizedName;

            refactoring.setGetterName(getterName);
            refactoring.setSetterName(setterName);

            RefactoringStatus status = refactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("fieldName", fieldName);
            result.put("getterName", getterName);
            result.put("setterName", setterName);

            if (status.hasError()) {
                result.put("status", "ERROR");
                result.put("message", "Encapsulate field has errors: " + status.toString());
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Field encapsulated successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error encapsulating field: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Introduce parameter (extract local variable as method parameter).
     */
    public static ToolRegistration introduceParameterTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to the Java file"),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Position of the local variable or expression to extract as parameter"),
                        "length", Map.of(
                                "type", "integer",
                                "description", "Length of selection (0 for variable at cursor)"),
                        "parameterName", Map.of(
                                "type", "string",
                                "description", "Name for the new parameter"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("filePath", "offset", "parameterName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_introduce_parameter",
                "Turn a local variable or expression into a METHOD PARAMETER. " +
                "Example: Inside 'void greet() { String name = \"World\"; }' you can extract 'name' " +
                "to become 'void greet(String name)'. All callers will be updated! " +
                "Use when you want to make a method more flexible/reusable.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> introduceParameter(
                (String) args.get("filePath"),
                ((Number) args.get("offset")).intValue(),
                args.get("length") != null ? ((Number) args.get("length")).intValue() : 0,
                (String) args.get("parameterName"),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    private static CallToolResult introduceParameter(String filePath, int offset, int length,
            String parameterName, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Create Introduce Parameter refactoring
            org.eclipse.jdt.internal.corext.refactoring.code.IntroduceParameterRefactoring refactoring =
                new org.eclipse.jdt.internal.corext.refactoring.code.IntroduceParameterRefactoring(
                    cu, offset, length);

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("offset", offset);
            result.put("parameterName", parameterName);

            // IMPORTANT: checkInitialConditions must be called BEFORE setParameterName
            // because it initializes the internal fParameter object
            RefactoringStatus initialStatus = refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initialStatus.hasFatalError()) {
                result.put("status", "ERROR");
                result.put("message", "Cannot introduce parameter at this location");
                result.put("errors", extractStatusMessages(initialStatus));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            // Now we can safely set the parameter name (fParameter is initialized)
            refactoring.setParameterName(parameterName);

            // Check final conditions and merge with initial status
            RefactoringStatus finalStatus = refactoring.checkFinalConditions(new NullProgressMonitor());
            RefactoringStatus status = new RefactoringStatus();
            status.merge(initialStatus);
            status.merge(finalStatus);

            if (status.hasError()) {
                result.put("status", "ERROR");
                result.put("message", "Introduce parameter has errors: " + status.toString());
                result.put("errors", extractStatusMessages(status));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (status.hasWarning()) {
                result.put("warnings", extractStatusMessages(status));
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Parameter introduced successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error introducing parameter: " + e.getMessage(), true);
        }
    }

    /**
     * Helper: Add import to set if it's a qualified type.
     */
    private static void addImportIfNeeded(String typeSignature, java.util.Set<String> imports) {
        if (typeSignature == null) return;
        String typeName = org.eclipse.jdt.core.Signature.toString(typeSignature);
        // Skip primitives and java.lang types
        if (typeName.contains(".") && !typeName.startsWith("java.lang.")) {
            imports.add(typeName);
        }
    }

    /**
     * Extract messages from RefactoringStatus.
     */
    private static List<String> extractStatusMessages(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .toList();
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
                McpLogger.info("RefactoringTools", "AST rename: searching references for "
                        + element.getClass().getSimpleName() + " '"
                        + element.getElementName() + "' in "
                        + (element.getJavaProject() != null ? element.getJavaProject().getElementName() : "null"));

                org.eclipse.jdt.core.search.SearchPattern pattern =
                        org.eclipse.jdt.core.search.SearchPattern.createPattern(
                                element,
                                org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES);
                McpLogger.info("RefactoringTools", "AST rename: search pattern = "
                        + (pattern != null ? pattern.getClass().getSimpleName() + ": " + pattern : "null"));

                if (pattern != null) {
                    org.eclipse.jdt.core.search.IJavaSearchScope scope =
                            org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope();
                    McpLogger.info("RefactoringTools", "AST rename: workspace scope enclosing projects = "
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
                    McpLogger.info("RefactoringTools", "AST rename: found " + allMatches.size()
                            + " accurate matches, " + allMatchesIncludingPotential.size() + " total (incl. potential)");
                    for (var m : allMatchesIncludingPotential) {
                        McpLogger.debug("RefactoringTools", "  match: accuracy="
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
            result.put("status", "SUCCESS");
            result.put("message", "Rename completed via AST-based fallback");
            result.put("oldName", oldName);
            result.put("newName", newName);
            result.put("changedFiles", changedFiles);
            result.put("totalReferences", allMatches.size());
            result.put("fallback", true);
            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            error.put("message", "Error during AST-based rename: " + msg);
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error during AST-based rename: " + msg, true);
            }
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

    /**
     * Checks refactoring status for real errors, filtering out harmless participant
     * errors that occur in headless mode (Launch/Breakpoint/Watchpoint participants).
     * Returns list of real error messages, or empty list if only participant errors.
     */
    private static List<String> getRealErrors(RefactoringStatus status) {
        if (!status.hasError()) {
            return List.of();
        }
        List<String> realErrors = new java.util.ArrayList<>();
        for (var entry : status.getEntries()) {
            if (entry.getSeverity() >= RefactoringStatus.ERROR) {
                String msg = entry.getMessage();
                // Skip harmless headless-mode errors
                if (msg != null && msg.contains("participant")) continue;
                // "potential matches" are informational, not blocking errors
                if (msg != null && msg.toLowerCase().contains("potential match")) continue;
                realErrors.add(msg);
            }
        }
        return realErrors;
    }

    /**
     * Extract non-participant warnings from refactoring status.
     */
    private static List<String> getNonParticipantWarnings(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .filter(msg -> msg == null || !msg.contains("participant"))
                .toList();
    }

    /**
     * Describe the changes that would be made.
     */
    private static Map<String, Object> describeChange(Change change) {
        Map<String, Object> desc = new HashMap<>();
        if (change != null) {
            desc.put("name", change.getName());

            if (change instanceof org.eclipse.ltk.core.refactoring.CompositeChange compositeChange) {
                List<Map<String, Object>> children = new java.util.ArrayList<>();
                for (Change child : compositeChange.getChildren()) {
                    children.add(describeChange(child));
                }
                desc.put("children", children);
                desc.put("childCount", children.size());
            }

            // Try to get affected files
            Object modifiedElement = change.getModifiedElement();
            if (modifiedElement != null) {
                desc.put("modifiedElement", modifiedElement.toString());
            }
        }
        return desc;
    }

    /**
     * Helper: Find a Java element by name and type.
     */
    private static IJavaElement findElement(String elementName, String elementType) {
        try {
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {

                switch (elementType.toUpperCase()) {
                    case "CLASS", "INTERFACE", "ENUM", "TYPE" -> {
                        IType type = project.findType(elementName);
                        if (type != null) {
                            return type;
                        }
                    }
                    case "METHOD" -> {
                        // Format: com.example.Class#methodName or com.example.Class.methodName
                        int separator = elementName.lastIndexOf('#');
                        if (separator == -1) {
                            separator = elementName.lastIndexOf('.');
                        }
                        if (separator > 0) {
                            String className = elementName.substring(0, separator);
                            String methodNamePart = elementName.substring(separator + 1);
                            IType type = project.findType(className);
                            if (type != null) {
                                for (IMethod method : type.getMethods()) {
                                    if (method.getElementName().equals(methodNamePart)) {
                                        return method;
                                    }
                                }
                            }
                        }
                    }
                    case "FIELD" -> {
                        // Format: com.example.Class#fieldName or com.example.Class.fieldName
                        int separator = elementName.lastIndexOf('#');
                        if (separator == -1) {
                            separator = elementName.lastIndexOf('.');
                        }
                        if (separator > 0) {
                            String className = elementName.substring(0, separator);
                            String fieldNamePart = elementName.substring(separator + 1);
                            IType type = project.findType(className);
                            if (type != null) {
                                IField field = type.getField(fieldNamePart);
                                if (field != null && field.exists()) {
                                    return field;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error finding element: " + e.getMessage());
        }
        return null;
    }
}
