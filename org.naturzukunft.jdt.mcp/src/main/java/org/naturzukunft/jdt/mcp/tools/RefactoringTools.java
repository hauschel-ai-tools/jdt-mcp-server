package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
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
 *
 * AI-HINT: This class was split as part of #30 (God Class refactoring).
 * Shared helpers live in {@link RefactoringSupport}. When adding new
 * refactoring tools, consider creating a separate class per concern
 * instead of growing this file further. Keep separation of concerns!
 */
@SuppressWarnings("restriction")
public class RefactoringTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Delegates to {@link RenameRefactoring}.
     */
    public static ToolRegistration renameElementTool() {
        return RenameRefactoring.renameElementTool();
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

            List<String> extractErrors = RefactoringSupport.getRealErrors(checkStatus);
            if (!extractErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Extract method has errors: " + String.join("; ", extractErrors));
                result.put("errors", extractErrors);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (checkStatus.hasWarning()) {
                result.put("warnings", RefactoringSupport.extractStatusMessages(checkStatus));
            }

            if (previewOnly) {
                Change change = extractRefactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of extract method refactoring");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = extractRefactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Extract method completed successfully");
            result.put("changes", RefactoringSupport.describeChange(change));

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("extract method", e);
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
            // Find the type in its source project
            IType type = RefactoringSupport.findTypeInSourceProject(typeName);

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

            List<String> moveErrors = RefactoringSupport.getRealErrors(checkStatus);
            if (!moveErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Move refactoring has errors: " + String.join("; ", moveErrors));
                result.put("errors", moveErrors);
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            List<String> moveWarnings = RefactoringSupport.getNonParticipantWarnings(checkStatus);
            if (!moveWarnings.isEmpty()) {
                result.put("warnings", moveWarnings);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of move refactoring");
                result.put("changes", RefactoringSupport.describeChange(change));
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
            return ToolErrors.errorResult("move type", e);
        }
    }

    /**
     * Tool: Organize imports in a Java file.
     */
    /**
     * Delegates to {@link ImportOrganizer}.
     */
    public static ToolRegistration organizeImportsTool() {
        return ImportOrganizer.organizeImportsTool();
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

                    List<String> inlineErrors = RefactoringSupport.getRealErrors(checkStatus);
                    if (!inlineErrors.isEmpty()) {
                        result.put("status", "ERROR");
                        result.put("message", "Inline has errors: " + String.join("; ", inlineErrors));
                        return new CallToolResult(MAPPER.writeValueAsString(result), true);
                    }

                    if (previewOnly) {
                        Change change = inlineTemp.createChange(new NullProgressMonitor());
                        result.put("status", "PREVIEW");
                        result.put("inlineType", "LOCAL_VARIABLE");
                        result.put("changes", RefactoringSupport.describeChange(change));
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

                            List<String> inlineErrors = RefactoringSupport.getRealErrors(checkStatus);
                            if (!inlineErrors.isEmpty()) {
                                result.put("status", "ERROR");
                                result.put("message", "Inline method has errors: " + String.join("; ", inlineErrors));
                                return new CallToolResult(MAPPER.writeValueAsString(result), true);
                            }

                            if (previewOnly) {
                                Change change = inlineMethod.createChange(new NullProgressMonitor());
                                result.put("status", "PREVIEW");
                                result.put("inlineType", "METHOD");
                                result.put("changes", RefactoringSupport.describeChange(change));
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
            return ToolErrors.errorResult("inline", e);
        }
    }

    /**
     * Delegates to {@link ExtractInterfaceRefactoring}.
     */
    public static ToolRegistration extractInterfaceTool() {
        return ExtractInterfaceRefactoring.extractInterfaceTool();
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
            IType type = RefactoringSupport.findTypeInSourceProject(className);

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

            List<String> realErrors = RefactoringSupport.getRealErrors(status);
            if (!realErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Change signature has errors: " + String.join("; ", realErrors));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Method signature changed successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("change method signature", e);
        }
    }

    /**
     * Delegates to {@link ConvertToLambdaRefactoring}.
     */
    public static ToolRegistration convertToLambdaTool() {
        return ConvertToLambdaRefactoring.convertToLambdaTool();
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
            IType type = RefactoringSupport.findTypeInSourceProject(className);

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
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Field encapsulated successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("encapsulate field", e);
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
                result.put("errors", RefactoringSupport.extractStatusMessages(initialStatus));
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
                result.put("errors", RefactoringSupport.extractStatusMessages(status));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (status.hasWarning()) {
                result.put("warnings", RefactoringSupport.extractStatusMessages(status));
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Parameter introduced successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("introduce parameter", e);
        }
    }

}
