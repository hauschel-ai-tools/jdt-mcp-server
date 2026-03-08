package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for Java code analysis using JDT.
 */
public class CodeAnalysisTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Parse Java file and return AST structure.
     */
    public static ToolRegistration parseJavaFileTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("filePath", Map.of(
                        "type", "string",
                        "description", "Absolute file path to Java source file")),
                List.of("filePath"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_parse_java_file",
                "Parse a Java source file and return its structure: package name, imports, classes/interfaces, " +
                "methods (with signatures), and fields. Use this to understand a file's content. " +
                "TIP: Call jdt_refresh_project first if you modified the file externally.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> parseJavaFile((String) args.get("filePath")));
    }

    private static CallToolResult parseJavaFile(String filePath) {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IFile file = root.getFileForLocation(new Path(filePath));

            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            Map<String, Object> result = new HashMap<>();

            // Package
            if (cu.getPackageDeclarations().length > 0) {
                result.put("packageName", cu.getPackageDeclarations()[0].getElementName());
            } else {
                result.put("packageName", "");
            }

            // Imports
            List<String> imports = new ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                imports.add(imp.getElementName());
            }
            result.put("imports", imports);

            // Types
            List<Map<String, Object>> types = new ArrayList<>();
            for (IType type : cu.getTypes()) {
                types.add(parseType(type));
            }
            result.put("types", types);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("parse java file", e);
        }
    }

    private static Map<String, Object> parseType(IType type) throws JavaModelException {
        Map<String, Object> typeInfo = new HashMap<>();
        typeInfo.put("name", type.getElementName());
        typeInfo.put("fullyQualifiedName", type.getFullyQualifiedName());
        typeInfo.put("isClass", type.isClass());
        typeInfo.put("isInterface", type.isInterface());
        typeInfo.put("isEnum", type.isEnum());
        typeInfo.put("isRecord", type.isRecord());

        // Superclass
        String superclass = type.getSuperclassName();
        typeInfo.put("superclass", superclass != null ? superclass : "");

        // Interfaces
        typeInfo.put("interfaces", List.of(type.getSuperInterfaceNames()));

        // Methods
        List<Map<String, Object>> methods = new ArrayList<>();
        for (IMethod method : type.getMethods()) {
            Map<String, Object> methodInfo = new HashMap<>();
            methodInfo.put("name", method.getElementName());
            methodInfo.put("signature", method.getSignature());
            methodInfo.put("returnType", method.getReturnType());
            methodInfo.put("parameters", List.of(method.getParameterNames()));
            methodInfo.put("isConstructor", method.isConstructor());
            methods.add(methodInfo);
        }
        typeInfo.put("methods", methods);

        // Fields
        List<Map<String, Object>> fields = new ArrayList<>();
        for (IField field : type.getFields()) {
            Map<String, Object> fieldInfo = new HashMap<>();
            fieldInfo.put("name", field.getElementName());
            fieldInfo.put("type", field.getTypeSignature());
            fields.add(fieldInfo);
        }
        typeInfo.put("fields", fields);

        return typeInfo;
    }

    /**
     * Tool: Get type hierarchy for a class.
     */
    public static ToolRegistration getTypeHierarchyTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("className", Map.of(
                        "type", "string",
                        "description", "Fully qualified class name = package + class name (e.g., 'java.util.ArrayList', 'com.example.MyClass'). Get from jdt_find_type or jdt_parse_java_file.")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_type_hierarchy",
                "Get inheritance hierarchy for a Java class/interface: all superclasses, implemented interfaces, " +
                "and subclasses/implementations. Use when you need to understand class relationships. " +
                "For finding ONLY implementations, use jdt_find_implementations instead.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getTypeHierarchy((String) args.get("className")));
    }

    private static CallToolResult getTypeHierarchy(String className) {
        try {
            IType type = findType(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ITypeHierarchy hierarchy = type.newTypeHierarchy(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("type", className);

            // Superclasses
            List<String> superclasses = new ArrayList<>();
            for (IType superType : hierarchy.getAllSuperclasses(type)) {
                superclasses.add(superType.getFullyQualifiedName());
            }
            result.put("superclasses", superclasses);

            // Interfaces
            List<String> interfaces = new ArrayList<>();
            for (IType iface : hierarchy.getAllSuperInterfaces(type)) {
                interfaces.add(iface.getFullyQualifiedName());
            }
            result.put("interfaces", interfaces);

            // Subclasses
            List<String> subclasses = new ArrayList<>();
            for (IType subType : hierarchy.getAllSubtypes(type)) {
                subclasses.add(subType.getFullyQualifiedName());
            }
            result.put("subclasses", subclasses);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("get type hierarchy", e);
        }
    }

    /**
     * Tool: Find all references to a Java element.
     */
    public static ToolRegistration findReferencesTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "Element to search for. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#methodName'. FIELD: 'com.example.MyClass#fieldName'"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Type of element: 'CLASS', 'METHOD', or 'FIELD'")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_references",
                "Find ALL usages of a class, method, or field across the entire workspace. " +
                "Returns file locations and offsets. Use this for impact analysis before refactoring. " +
                "For METHOD callers specifically, jdt_find_callers gives more detailed caller info.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findReferences(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult findReferences(String elementName, String elementType) {
        try {
            // Resolve element via Java model for reliable cross-module search
            IJavaElement element = resolveElement(elementName, elementType);
            if (element == null) {
                return new CallToolResult("Element not found: " + elementName + " (type: " + elementType + ")", true);
            }

            SearchPattern pattern = SearchPattern.createPattern(
                    element,
                    IJavaSearchConstants.REFERENCES);

            if (pattern == null) {
                return new CallToolResult("Could not create search pattern for: " + elementName, true);
            }

            // Explicit scope with all projects for cross-module resolution
            IJavaProject[] allProjects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects();
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(allProjects);

            List<Map<String, Object>> references = new ArrayList<>();
            SearchRequestor requestor = new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    Map<String, Object> ref = new HashMap<>();
                    ref.put("file", match.getResource() != null
                            ? match.getResource().getLocation().toString()
                            : "unknown");
                    ref.put("offset", match.getOffset());
                    ref.put("length", match.getLength());
                    ref.put("accuracy", match.getAccuracy() == SearchMatch.A_ACCURATE
                            ? "ACCURATE"
                            : "INACCURATE");
                    references.add(ref);
                }
            };

            new SearchEngine().search(
                    pattern,
                    new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                    scope,
                    requestor,
                    new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("element", elementName);
            result.put("type", elementType);
            result.put("referenceCount", references.size());
            result.put("references", references);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("find references", e);
        }
    }

    /**
     * Tool: Get source code of a method, class, or field.
     */
    public static ToolRegistration getSourceRangeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "elementName", Map.of(
                                "type", "string",
                                "description", "Element to get source for. CLASS: 'com.example.MyClass'. METHOD: 'com.example.MyClass#methodName'. FIELD: 'com.example.MyClass#fieldName'"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Type of element: 'CLASS', 'METHOD', or 'FIELD'")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_source_range",
                "GET THE ACTUAL SOURCE CODE of a class, method, or field as text. " +
                "Unlike jdt_parse_java_file which returns structure, this returns the raw code. " +
                "Use when you need to see the implementation details or copy/modify code.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getSourceRange(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult getSourceRange(String elementName, String elementType) {
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

            IType type = findType(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot get source for binary type: " + className, true);
            }

            String fullSource = cu.getSource();
            String source = null;
            int offset = 0;
            int length = 0;

            switch (elementType.toUpperCase()) {
                case "CLASS" -> {
                    if (type.getSourceRange() != null) {
                        offset = type.getSourceRange().getOffset();
                        length = type.getSourceRange().getLength();
                        source = fullSource.substring(offset, offset + length);
                    }
                }
                case "METHOD" -> {
                    if (memberName != null) {
                        for (IMethod method : type.getMethods()) {
                            if (method.getElementName().equals(memberName) && method.getSourceRange() != null) {
                                offset = method.getSourceRange().getOffset();
                                length = method.getSourceRange().getLength();
                                source = fullSource.substring(offset, offset + length);
                                break;
                            }
                        }
                    }
                }
                case "FIELD" -> {
                    if (memberName != null) {
                        IField field = type.getField(memberName);
                        if (field != null && field.exists() && field.getSourceRange() != null) {
                            offset = field.getSourceRange().getOffset();
                            length = field.getSourceRange().getLength();
                            source = fullSource.substring(offset, offset + length);
                        }
                    }
                }
            }

            if (source == null) {
                return new CallToolResult("Could not get source for: " + elementName, true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("elementName", elementName);
            result.put("elementType", elementType);
            result.put("source", source);
            result.put("offset", offset);
            result.put("length", length);
            result.put("lineCount", source.split("\n").length);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("get source range", e);
        }
    }

    /**
     * Resolves a Java element (type, method, or field) from a qualified name.
     */
    private static IJavaElement resolveElement(String elementName, String elementType) {
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

            IType type = findType(className);
            if (type == null) {
                return null;
            }

            return switch (elementType.toUpperCase()) {
                case "CLASS", "TYPE", "INTERFACE", "ENUM" -> type;
                case "METHOD" -> {
                    if (memberName != null) {
                        for (IMethod method : type.getMethods()) {
                            if (method.getElementName().equals(memberName)) {
                                yield method;
                            }
                        }
                    }
                    yield null;
                }
                case "FIELD" -> {
                    if (memberName != null) {
                        IField field = type.getField(memberName);
                        if (field != null && field.exists()) {
                            yield field;
                        }
                    }
                    yield null;
                }
                default -> null;
            };
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error resolving element: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper: Find a type by fully qualified name.
     */
    private static IType findType(String fullyQualifiedName) {
        try {
            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                IType type = project.findType(fullyQualifiedName);
                if (type != null) {
                    return type;
                }
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error finding type: " + e.getMessage());
        }
        return null;
    }
}
