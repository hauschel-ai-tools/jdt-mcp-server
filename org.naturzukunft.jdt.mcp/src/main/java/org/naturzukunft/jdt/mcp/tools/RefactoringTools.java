package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
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
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.swt.widgets.Display;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for Java refactoring using JDT.
 */
public class RefactoringTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Rename a Java element.
     */
    public static ToolRegistration renameElementTool() {
        Tool tool = new Tool(
                "jdt_rename_element",
                "Rename a Java element (class, method, field) with full refactoring support",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "elementName", Map.of(
                                        "type", "string",
                                        "description", "Fully qualified name of element to rename"),
                                "newName", Map.of(
                                        "type", "string",
                                        "description", "New name for the element"),
                                "elementType", Map.of(
                                        "type", "string",
                                        "description", "Element type: CLASS, METHOD, or FIELD")),
                        "required", List.of("elementName", "newName", "elementType")));

        return new ToolRegistration(tool, args -> renameElement(
                (String) args.get("elementName"),
                (String) args.get("newName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult renameElement(String elementName, String newName, String elementType) {
        try {
            // Find the element
            IJavaElement element = findElement(elementName, elementType);
            if (element == null) {
                return errorResult("Element not found: " + elementName + " (type: " + elementType + ")");
            }

            // Prepare result holder for UI thread execution
            final Map<String, Object> result = new HashMap<>();
            final List<Exception> errors = new ArrayList<>();

            // Refactoring must run on UI thread
            Display.getDefault().syncExec(() -> {
                try {
                    // Create rename descriptor
                    RefactoringContribution contribution = RefactoringCore
                            .getRefactoringContribution(IJavaRefactorings.RENAME_TYPE);

                    if (contribution == null) {
                        result.put("error", "Rename refactoring not available");
                        return;
                    }

                    RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution
                            .createDescriptor();

                    descriptor.setJavaElement(element);
                    descriptor.setNewName(newName);
                    descriptor.setUpdateReferences(true);

                    // Create and check refactoring
                    RefactoringStatus status = new RefactoringStatus();
                    Refactoring refactoring = descriptor.createRefactoring(status);

                    if (status.hasFatalError()) {
                        result.put("status", "FATAL_ERROR");
                        result.put("message", status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
                        return;
                    }

                    // Check preconditions
                    status = refactoring.checkAllConditions(new NullProgressMonitor());
                    if (status.hasFatalError()) {
                        result.put("status", "PRECONDITION_FAILED");
                        result.put("message", status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
                        return;
                    }

                    // Execute refactoring
                    Change change = refactoring.createChange(new NullProgressMonitor());
                    change.perform(new NullProgressMonitor());

                    result.put("status", "SUCCESS");
                    result.put("oldName", elementName);
                    result.put("newName", newName);
                    result.put("elementType", elementType);

                    // Collect affected files
                    List<String> affectedFiles = new ArrayList<>();
                    collectAffectedFiles(change, affectedFiles);
                    result.put("affectedFiles", affectedFiles);

                } catch (CoreException e) {
                    errors.add(e);
                }
            });

            if (!errors.isEmpty()) {
                return errorResult("Refactoring failed: " + errors.get(0).getMessage());
            }

            if (result.containsKey("error")) {
                return errorResult((String) result.get("error"));
            }

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error during rename: " + e.getMessage());
        }
    }

    /**
     * Tool: Extract method.
     */
    public static ToolRegistration extractMethodTool() {
        Tool tool = new Tool(
                "jdt_extract_method",
                "Extract selected code into a new method (Preview only - returns what would be extracted)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "filePath", Map.of(
                                        "type", "string",
                                        "description", "Absolute file path"),
                                "startOffset", Map.of(
                                        "type", "integer",
                                        "description", "Selection start offset in characters"),
                                "endOffset", Map.of(
                                        "type", "integer",
                                        "description", "Selection end offset in characters"),
                                "methodName", Map.of(
                                        "type", "string",
                                        "description", "Name for the extracted method")),
                        "required", List.of("filePath", "startOffset", "endOffset", "methodName")));

        return new ToolRegistration(tool, args -> extractMethod(
                (String) args.get("filePath"),
                ((Number) args.get("startOffset")).intValue(),
                ((Number) args.get("endOffset")).intValue(),
                (String) args.get("methodName")));
    }

    private static CallToolResult extractMethod(String filePath, int startOffset, int endOffset, String methodName) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return errorResult("File not found: " + filePath);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return errorResult("Not a Java source file: " + filePath);
            }

            // Get the selected text
            String source = cu.getSource();
            if (startOffset < 0 || endOffset > source.length() || startOffset >= endOffset) {
                return errorResult("Invalid selection range: " + startOffset + "-" + endOffset);
            }

            String selectedText = source.substring(startOffset, endOffset);

            // For now, return a preview of what would be extracted
            // Full extract method refactoring requires more complex setup
            Map<String, Object> result = new HashMap<>();
            result.put("status", "PREVIEW");
            result.put("message", "Extract method preview (full refactoring requires UI interaction)");
            result.put("filePath", filePath);
            result.put("startOffset", startOffset);
            result.put("endOffset", endOffset);
            result.put("proposedMethodName", methodName);
            result.put("selectedCode", selectedText);
            result.put("hint", "Use Eclipse IDE to perform the actual extract method refactoring");

            return successResult(MAPPER.writeValueAsString(result));

        } catch (Exception e) {
            return errorResult("Error during extract method: " + e.getMessage());
        }
    }

    /**
     * Helper: Find a Java element by name and type.
     */
    private static IJavaElement findElement(String elementName, String elementType) {
        try {
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {

                switch (elementType.toUpperCase()) {
                    case "CLASS", "INTERFACE", "ENUM" -> {
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
                            String methodName = elementName.substring(separator + 1);
                            IType type = project.findType(className);
                            if (type != null) {
                                for (IMethod method : type.getMethods()) {
                                    if (method.getElementName().equals(methodName)) {
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
                            String fieldName = elementName.substring(separator + 1);
                            IType type = project.findType(className);
                            if (type != null) {
                                IField field = type.getField(fieldName);
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

    /**
     * Helper: Collect affected files from a change.
     */
    private static void collectAffectedFiles(Change change, List<String> files) {
        if (change == null) {
            return;
        }

        Object modified = change.getModifiedElement();
        if (modified instanceof IFile file) {
            files.add(file.getLocation().toString());
        }

        // Note: For composite changes, would need to recurse into children
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
