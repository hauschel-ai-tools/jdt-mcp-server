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
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
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
                                "description", "Fully qualified name of element to rename (e.g., 'com.example.MyClass' or 'com.example.MyClass#methodName')"),
                        "newName", Map.of(
                                "type", "string",
                                "description", "New name for the element (simple name only, e.g., 'NewClassName')"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Element type: CLASS, METHOD, or FIELD"),
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
                "Rename a Java element (class, method, field) and update all references. Performs actual refactoring.",
                schema,
                null);

        return new ToolRegistration(tool, args -> renameElement(
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

            // Determine the refactoring ID based on element type
            String refactoringId = getRefactoringId(element);
            if (refactoringId == null) {
                return new CallToolResult("Unsupported element type for rename: " + element.getClass().getSimpleName(), true);
            }

            // Get the refactoring contribution
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(refactoringId);
            if (contribution == null) {
                return new CallToolResult("Refactoring not available: " + refactoringId, true);
            }

            // Create the rename descriptor
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();
            descriptor.setJavaElement(element);
            descriptor.setNewName(newName);
            descriptor.setUpdateReferences(updateReferences);

            // Configure additional options based on element type
            if (element instanceof IType) {
                descriptor.setUpdateQualifiedNames(true);
                descriptor.setUpdateSimilarDeclarations(false);
            } else if (element instanceof IMethod) {
                descriptor.setKeepOriginal(false);
                descriptor.setDeprecateDelegate(false);
            } else if (element instanceof IField) {
                descriptor.setRenameGetters(true);
                descriptor.setRenameSetters(true);
            }

            // Create the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);

            if (refactoring == null) {
                return new CallToolResult("Could not create refactoring: " + status.toString(), true);
            }

            // Check preconditions
            RefactoringStatus checkStatus = refactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("newName", newName);
            result.put("elementType", elementType);
            result.put("updateReferences", updateReferences);

            if (checkStatus.hasError()) {
                result.put("status", "ERROR");
                result.put("message", "Refactoring has errors: " + checkStatus.toString());
                result.put("errors", extractStatusMessages(checkStatus));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (checkStatus.hasWarning()) {
                result.put("warnings", extractStatusMessages(checkStatus));
            }

            if (previewOnly) {
                // Preview mode - just return what would be changed
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of rename refactoring");
                result.put("changes", describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Execute the refactoring
            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Refactoring completed successfully");
            result.put("changes", describeChange(change));

            // Add element details
            if (element.getResource() != null) {
                result.put("file", element.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error during rename: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error during rename: " + e.getMessage(), true);
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
                                "description", "Absolute file path to Java source file"),
                        "startOffset", Map.of(
                                "type", "integer",
                                "description", "Selection start offset in characters"),
                        "endOffset", Map.of(
                                "type", "integer",
                                "description", "Selection end offset in characters"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Name for the extracted method"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "If true, only preview changes without applying (default: false)")),
                List.of("filePath", "startOffset", "endOffset", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_extract_method",
                "Extract selected code into a new method. Performs actual refactoring.",
                schema,
                null);

        return new ToolRegistration(tool, args -> extractMethod(
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

            if (checkStatus.hasError()) {
                result.put("status", "ERROR");
                result.put("message", "Extract method has errors: " + checkStatus.toString());
                result.put("errors", extractStatusMessages(checkStatus));
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
     * Get the appropriate refactoring ID for an element.
     */
    private static String getRefactoringId(IJavaElement element) {
        if (element instanceof IType) {
            return IJavaRefactorings.RENAME_TYPE;
        } else if (element instanceof IMethod) {
            return IJavaRefactorings.RENAME_METHOD;
        } else if (element instanceof IField) {
            return IJavaRefactorings.RENAME_FIELD;
        } else if (element instanceof ICompilationUnit) {
            return IJavaRefactorings.RENAME_COMPILATION_UNIT;
        }
        return null;
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
