package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for Java code navigation using JDT.
 */
public class NavigationTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Search for types in workspace.
     */
    public static ToolRegistration findTypeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("pattern", Map.of(
                        "type", "string",
                        "description", "Type name pattern (e.g., 'ArrayList', '*Service', 'User*')")),
                List.of("pattern"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_type",
                "Search for Java types (classes, interfaces, enums) by name pattern. Supports wildcards (* and ?)",
                schema,
                null);

        return new ToolRegistration(tool, args -> findType((String) args.get("pattern")));
    }

    private static CallToolResult findType(String pattern) {
        try {
            List<Map<String, Object>> matches = new ArrayList<>();

            SearchEngine engine = new SearchEngine();
            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

            TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
                @Override
                public void acceptTypeNameMatch(TypeNameMatch match) {
                    Map<String, Object> typeInfo = new HashMap<>();
                    typeInfo.put("simpleName", match.getSimpleTypeName());
                    typeInfo.put("fullyQualifiedName", match.getFullyQualifiedName());
                    typeInfo.put("packageName", match.getPackageName());

                    IType type = match.getType();
                    if (type != null && type.getResource() != null) {
                        typeInfo.put("file", type.getResource().getLocation().toString());
                    }

                    try {
                        typeInfo.put("isClass", type != null && type.isClass());
                        typeInfo.put("isInterface", type != null && type.isInterface());
                        typeInfo.put("isEnum", type != null && type.isEnum());
                    } catch (Exception e) {
                        // Ignore
                    }

                    matches.add(typeInfo);
                }
            };

            // Convert simple pattern to search pattern
            char[] patternChars = pattern.toCharArray();

            engine.searchAllTypeNames(
                    null, // package pattern
                    SearchPattern.R_PATTERN_MATCH,
                    patternChars,
                    SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE,
                    IJavaSearchConstants.TYPE,
                    scope,
                    requestor,
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("pattern", pattern);
            result.put("matchCount", matches.size());
            result.put("matches", matches);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error finding types: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Get detailed method signature information.
     */
    public static ToolRegistration getMethodSignatureTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Method name (use * to get all methods)")),
                List.of("className", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_method_signature",
                "Get method signature with parameters, return type, and modifiers",
                schema,
                null);

        return new ToolRegistration(tool, args -> getMethodSignature(
                (String) args.get("className"),
                (String) args.get("methodName")));
    }

    private static CallToolResult getMethodSignature(String className, String methodName) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            List<Map<String, Object>> methods = new ArrayList<>();

            for (IMethod method : type.getMethods()) {
                if ("*".equals(methodName) || method.getElementName().equals(methodName)) {
                    Map<String, Object> methodInfo = new HashMap<>();
                    methodInfo.put("name", method.getElementName());
                    methodInfo.put("signature", method.getSignature());
                    methodInfo.put("returnType", method.getReturnType());
                    methodInfo.put("isConstructor", method.isConstructor());

                    // Parameters
                    List<Map<String, String>> params = new ArrayList<>();
                    String[] paramNames = method.getParameterNames();
                    String[] paramTypes = method.getParameterTypes();
                    for (int i = 0; i < paramNames.length; i++) {
                        Map<String, String> param = new HashMap<>();
                        param.put("name", paramNames[i]);
                        param.put("type", paramTypes[i]);
                        params.add(param);
                    }
                    methodInfo.put("parameters", params);

                    // Exceptions
                    methodInfo.put("exceptions", List.of(method.getExceptionTypes()));

                    // Flags
                    int flags = method.getFlags();
                    methodInfo.put("flags", flags);

                    // Source range
                    if (method.getSourceRange() != null) {
                        methodInfo.put("sourceOffset", method.getSourceRange().getOffset());
                        methodInfo.put("sourceLength", method.getSourceRange().getLength());
                    }

                    methods.add(methodInfo);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("methodCount", methods.size());
            result.put("methods", methods);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error getting method signature: " + e.getMessage(), true);
        }
    }

    /**
     * Helper: Find a type by fully qualified name.
     */
    private static IType findTypeByName(String fullyQualifiedName) {
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
