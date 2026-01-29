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
                "Parse Java source file and return structure (package, imports, types, methods, fields)",
                schema,
                null);

        return new ToolRegistration(tool, args -> parseJavaFile((String) args.get("filePath")));
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
            return new CallToolResult("Error parsing file: " + e.getMessage(), true);
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
                        "description", "Fully qualified class name (e.g., java.util.ArrayList)")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_type_hierarchy",
                "Get complete type hierarchy (superclasses, interfaces, subclasses) for a Java type",
                schema,
                null);

        return new ToolRegistration(tool, args -> getTypeHierarchy((String) args.get("className")));
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
            return new CallToolResult("Error getting type hierarchy: " + e.getMessage(), true);
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
                                "description", "Fully qualified element name"),
                        "elementType", Map.of(
                                "type", "string",
                                "description", "Element type: CLASS, METHOD, or FIELD")),
                List.of("elementName", "elementType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_references",
                "Find all references to a class, method, or field in the workspace",
                schema,
                null);

        return new ToolRegistration(tool, args -> findReferences(
                (String) args.get("elementName"),
                (String) args.get("elementType")));
    }

    private static CallToolResult findReferences(String elementName, String elementType) {
        try {
            int searchFor = switch (elementType.toUpperCase()) {
                case "CLASS" -> IJavaSearchConstants.CLASS;
                case "METHOD" -> IJavaSearchConstants.METHOD;
                case "FIELD" -> IJavaSearchConstants.FIELD;
                default -> IJavaSearchConstants.TYPE;
            };

            SearchPattern pattern = SearchPattern.createPattern(
                    elementName,
                    searchFor,
                    IJavaSearchConstants.REFERENCES,
                    SearchPattern.R_EXACT_MATCH);

            if (pattern == null) {
                return new CallToolResult("Could not create search pattern for: " + elementName, true);
            }

            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

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
            return new CallToolResult("Error finding references: " + e.getMessage(), true);
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
