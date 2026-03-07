package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for Javadoc and Annotation handling using JDT.
 */
public class DocumentationTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Get Javadoc for a class, method, or field.
     */
    public static ToolRegistration getJavadocTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "Element to get Javadoc for. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#methodName'. FIELD: 'com.example.MyClass#fieldName'"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Type of element: 'CLASS', 'METHOD', or 'FIELD'")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_javadoc",
                "Get Javadoc documentation for a class, method, or field. " +
                "Returns the raw Javadoc comment text including @param, @return, @throws tags. " +
                "Use this to understand API documentation without reading the full source file.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getJavadoc(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult getJavadoc(String elementName, String elementType) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("elementType", elementType);

            IMember member = findMember(elementName, elementType);
            if (member == null) {
                return new CallToolResult("Element not found: " + elementName + " (" + elementType + ")", true);
            }

            // Get Javadoc range
            ISourceRange javadocRange = member.getJavadocRange();
            if (javadocRange == null) {
                result.put("hasJavadoc", false);
                result.put("javadoc", null);
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Get the source and extract Javadoc
            String source = member.getCompilationUnit().getSource();
            String javadoc = source.substring(
                    javadocRange.getOffset(),
                    javadocRange.getOffset() + javadocRange.getLength());

            result.put("hasJavadoc", true);
            result.put("javadoc", javadoc);
            result.put("offset", javadocRange.getOffset());
            result.put("length", javadocRange.getLength());

            // Try to get attached Javadoc (from JAR sources)
            try {
                String attachedJavadoc = member.getAttachedJavadoc(new NullProgressMonitor());
                if (attachedJavadoc != null) {
                    result.put("attachedJavadoc", attachedJavadoc);
                }
            } catch (Exception e) {
                // Attached Javadoc not available, ignore
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting Javadoc: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Get annotations for a class, method, or field.
     */
    public static ToolRegistration getAnnotationsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "Element to get annotations for. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#methodName'. FIELD: 'com.example.MyClass#fieldName'"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Type of element: 'CLASS', 'METHOD', or 'FIELD'")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_annotations",
                "Get all annotations on a class, method, or field with their values. " +
                "Returns annotation names and parameter values (e.g., @Entity, @Column(name=\"user_id\")). " +
                "Use this to understand how elements are configured (JPA, Spring, etc.).",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getAnnotations(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult getAnnotations(String elementName, String elementType) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("elementType", elementType);

            IMember member = findMember(elementName, elementType);
            if (member == null) {
                return new CallToolResult("Element not found: " + elementName + " (" + elementType + ")", true);
            }

            if (!(member instanceof IAnnotatable annotatable)) {
                return new CallToolResult("Element does not support annotations: " + elementName, true);
            }

            List<Map<String, Object>> annotations = new ArrayList<>();
            for (IAnnotation annotation : annotatable.getAnnotations()) {
                Map<String, Object> annotationInfo = new HashMap<>();
                annotationInfo.put("name", annotation.getElementName());

                // Get annotation values
                Map<String, Object> values = new HashMap<>();
                for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
                    Object value = pair.getValue();
                    // Handle arrays
                    if (value instanceof Object[] arr) {
                        List<String> valueList = new ArrayList<>();
                        for (Object v : arr) {
                            valueList.add(String.valueOf(v));
                        }
                        values.put(pair.getMemberName(), valueList);
                    } else {
                        values.put(pair.getMemberName(), String.valueOf(value));
                    }
                }
                annotationInfo.put("values", values);

                // Source info
                ISourceRange range = annotation.getSourceRange();
                if (range != null) {
                    annotationInfo.put("offset", range.getOffset());
                    annotationInfo.put("length", range.getLength());
                }

                annotations.add(annotationInfo);
            }

            result.put("annotationCount", annotations.size());
            result.put("annotations", annotations);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting annotations: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Find all elements annotated with a specific annotation.
     */
    public static ToolRegistration findAnnotatedElementsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "annotationName", Map.of(
                                "type", "string",
                                "description", "Annotation name to search for. Simple name: 'Entity', 'Service', 'Test'. Or fully qualified: 'org.springframework.stereotype.Service'"),
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Project name to search in (from jdt_list_projects). Optional - searches all projects if not specified.")),
                List.of("annotationName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_annotated_elements",
                "Find ALL classes, methods, or fields annotated with a specific annotation. " +
                "Examples: Find all @Entity classes, all @Test methods, all @Autowired fields. " +
                "Use simple name (@Service) or fully qualified (org.springframework.stereotype.Service).",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findAnnotatedElements(
                (String) args.get("annotationName"),
                (String) args.get("projectName")));
    }

    private static CallToolResult findAnnotatedElements(String annotationName, String projectName) {
        try {
            List<Map<String, Object>> annotatedElements = new ArrayList<>();

            // Determine search scope
            IJavaSearchScope scope;
            if (projectName != null && !projectName.isEmpty()) {
                IJavaProject project = JavaCore.create(
                        ResourcesPlugin.getWorkspace().getRoot().getProject(projectName));
                if (project == null || !project.exists()) {
                    return new CallToolResult("Project not found: " + projectName, true);
                }
                scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { project });
            } else {
                IJavaProject[] allProjects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                        .getJavaProjects();
                scope = SearchEngine.createJavaSearchScope(allProjects);
            }

            // Search for annotation type references
            SearchPattern pattern = SearchPattern.createPattern(
                    annotationName,
                    IJavaSearchConstants.ANNOTATION_TYPE,
                    IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                    SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

            if (pattern == null) {
                // Try with simple name matching
                pattern = SearchPattern.createPattern(
                        annotationName,
                        IJavaSearchConstants.ANNOTATION_TYPE,
                        IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                        SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE);
            }

            if (pattern == null) {
                return new CallToolResult("Could not create search pattern for annotation: " + annotationName, true);
            }

            SearchRequestor requestor = new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    Object element = match.getElement();
                    if (element instanceof IMember member) {
                        Map<String, Object> info = new HashMap<>();

                        if (member instanceof IType type) {
                            info.put("kind", "CLASS");
                            info.put("name", type.getFullyQualifiedName());
                        } else if (member instanceof IMethod method) {
                            info.put("kind", "METHOD");
                            info.put("name", method.getDeclaringType().getFullyQualifiedName() + "#" + method.getElementName());
                        } else if (member instanceof IField field) {
                            info.put("kind", "FIELD");
                            info.put("name", field.getDeclaringType().getFullyQualifiedName() + "#" + field.getElementName());
                        } else {
                            info.put("kind", "OTHER");
                            info.put("name", member.getElementName());
                        }

                        if (match.getResource() != null) {
                            info.put("file", match.getResource().getLocation().toString());
                        }
                        info.put("offset", match.getOffset());

                        annotatedElements.add(info);
                    }
                }
            };

            new SearchEngine().search(
                    pattern,
                    new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                    scope,
                    requestor,
                    new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("annotationName", annotationName);
            result.put("projectName", projectName != null ? projectName : "all");
            result.put("matchCount", annotatedElements.size());
            result.put("elements", annotatedElements);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error finding annotated elements: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Generate Javadoc for a method or class.
     */
    public static ToolRegistration generateJavadocTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "Element to generate Javadoc for. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#methodName'"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Type of element: 'CLASS' or 'METHOD'")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_javadoc",
                "Generate Javadoc comment stub for a class or method. " +
                "For methods: generates @param for each parameter, @return (if not void), @throws for declared exceptions. " +
                "Inserts the Javadoc directly before the element in the source file.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateJavadoc(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult generateJavadoc(String elementName, String elementType) {
        try {
            IMember member = findMember(elementName, elementType);
            if (member == null) {
                return new CallToolResult("Element not found: " + elementName + " (" + elementType + ")", true);
            }

            // Check if already has Javadoc
            if (member.getJavadocRange() != null) {
                return new CallToolResult("Element already has Javadoc. Use jdt_get_javadoc to read it.", true);
            }

            ICompilationUnit cu = member.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify binary class", true);
            }

            // Build Javadoc comment
            StringBuilder javadoc = new StringBuilder();
            javadoc.append("/**\n");

            if (member instanceof IType type) {
                javadoc.append(" * TODO: Add class description.\n");
                if (type.getTypeParameters().length > 0) {
                    for (var param : type.getTypeParameters()) {
                        javadoc.append(" * @param <").append(param.getElementName()).append("> TODO: describe type parameter\n");
                    }
                }
            } else if (member instanceof IMethod method) {
                javadoc.append(" * TODO: Add method description.\n");
                javadoc.append(" *\n");

                // @param tags
                String[] paramNames = method.getParameterNames();
                String[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramNames.length; i++) {
                    javadoc.append(" * @param ").append(paramNames[i]).append(" TODO: describe parameter\n");
                }

                // @return tag
                String returnType = method.getReturnType();
                if (returnType != null && !"V".equals(returnType)) { // V = void
                    javadoc.append(" * @return TODO: describe return value\n");
                }

                // @throws tags
                String[] exceptions = method.getExceptionTypes();
                for (String ex : exceptions) {
                    // Convert signature to simple name
                    String exName = ex.startsWith("Q") ? ex.substring(1, ex.length() - 1) : ex;
                    javadoc.append(" * @throws ").append(exName).append(" TODO: describe when thrown\n");
                }
            }

            javadoc.append(" */\n");

            // Get insert position (before the member declaration)
            ISourceRange sourceRange = member.getSourceRange();
            int insertOffset = sourceRange.getOffset();

            // Get current source and insert Javadoc
            String source = cu.getSource();

            // Find proper indentation
            int lineStart = source.lastIndexOf('\n', insertOffset - 1) + 1;
            String indent = "";
            for (int i = lineStart; i < insertOffset && Character.isWhitespace(source.charAt(i)); i++) {
                indent += source.charAt(i);
            }

            // Add indentation to each line of Javadoc
            String indentedJavadoc = javadoc.toString().replace("\n", "\n" + indent);
            if (indentedJavadoc.endsWith(indent)) {
                indentedJavadoc = indentedJavadoc.substring(0, indentedJavadoc.length() - indent.length());
            }

            // Create working copy and apply edit
            ICompilationUnit workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
            try {
                String newSource = source.substring(0, insertOffset) + indent + indentedJavadoc + source.substring(insertOffset);
                workingCopy.getBuffer().setContents(newSource);
                workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
            } finally {
                workingCopy.discardWorkingCopy();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("elementType", elementType);
            result.put("javadocGenerated", javadoc.toString());
            result.put("insertedAt", insertOffset);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error generating Javadoc: " + e.getMessage(), true);
        }
    }

    /**
     * Helper: Find a member (type, method, or field) by name.
     */
    private static IMember findMember(String elementName, String elementType) {
        try {
            String className;
            String memberName = null;

            if (elementName.contains("#")) {
                String[] parts = elementName.split("#", 2);
                className = parts[0];
                memberName = parts[1];
            } else {
                className = elementName;
            }

            // Find the type
            IType type = null;
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(className);
                if (type != null) {
                    break;
                }
            }

            if (type == null) {
                return null;
            }

            // Return based on element type
            switch (elementType.toUpperCase()) {
                case "CLASS":
                    return type;
                case "METHOD":
                    if (memberName != null) {
                        for (IMethod method : type.getMethods()) {
                            if (method.getElementName().equals(memberName)) {
                                return method;
                            }
                        }
                    }
                    return null;
                case "FIELD":
                    if (memberName != null) {
                        return type.getField(memberName);
                    }
                    return null;
                default:
                    return type;
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error finding member: " + e.getMessage());
            return null;
        }
    }
}
