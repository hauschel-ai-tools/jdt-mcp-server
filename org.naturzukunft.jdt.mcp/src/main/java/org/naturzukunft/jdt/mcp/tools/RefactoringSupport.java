package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * Shared utility methods for refactoring tools.
 *
 * Extracted from RefactoringTools to improve separation of concerns.
 * Contains element lookup, status analysis, and change description helpers
 * used across all refactoring operations.
 */
class RefactoringSupport {

    private RefactoringSupport() {
        // utility class
    }

    /**
     * Finds IType in the project that OWNS the source file (non-binary).
     * This is critical for refactoring: the element must come from the
     * declaring project so that ICompilationUnit is editable and bindings
     * are resolved via source, not class files.
     */
    static IType findTypeInSourceProject(String fullyQualifiedName) throws Exception {
        IType fallback = null;
        for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects()) {
            if (!project.getProject().isOpen()) continue;
            IType candidate = project.findType(fullyQualifiedName);
            if (candidate == null) continue;
            // Ideal: source type whose resource lives in this project
            if (!candidate.isBinary() && candidate.getResource() != null) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * Helper: Find a Java element by name and type.
     */
    static IJavaElement findElement(String elementName, String elementType) {
        try {
            switch (elementType.toUpperCase()) {
                case "CLASS", "INTERFACE", "ENUM", "TYPE" -> {
                    return findTypeInSourceProject(elementName);
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
                        IType type = findTypeInSourceProject(className);
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
                        IType type = findTypeInSourceProject(className);
                        if (type != null) {
                            IField field = type.getField(fieldNamePart);
                            if (field != null && field.exists()) {
                                return field;
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
     * Checks refactoring status for real errors, filtering out harmless participant
     * errors that occur in headless mode (Launch/Breakpoint/Watchpoint participants).
     * Returns list of real error messages, or empty list if only participant errors.
     */
    static List<String> getRealErrors(RefactoringStatus status) {
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
    static List<String> getNonParticipantWarnings(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .filter(msg -> msg == null || !msg.contains("participant"))
                .toList();
    }

    /**
     * Extract messages from RefactoringStatus.
     */
    static List<String> extractStatusMessages(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .toList();
    }

    /**
     * Count the total number of leaf (non-composite) changes in a change tree.
     * For rename, each leaf typically represents one file modification.
     */
    static int countLeafChanges(Change change) {
        if (change == null) {
            return 0;
        }
        if (change instanceof CompositeChange compositeChange) {
            int count = 0;
            for (Change child : compositeChange.getChildren()) {
                count += countLeafChanges(child);
            }
            return count;
        }
        return 1;
    }

    /**
     * Describe the changes that would be made.
     */
    static Map<String, Object> describeChange(Change change) {
        Map<String, Object> desc = new HashMap<>();
        if (change != null) {
            desc.put("name", change.getName());

            if (change instanceof CompositeChange compositeChange) {
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
}
