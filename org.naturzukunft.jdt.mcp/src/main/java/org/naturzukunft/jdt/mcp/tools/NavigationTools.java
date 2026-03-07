package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchRequestor;
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
                "🔍 FIND A CLASS: Don't know where a class is? Use this! " +
                "Search by name with wildcards: '*Service' finds UserService, OrderService, etc. " +
                "RETURNS: Fully qualified names like 'com.example.UserService' - USE THESE in jdt_get_method_signature, jdt_find_implementations, jdt_rename_element, etc. " +
                "WORKFLOW: User asks 'find the service class' → jdt_find_type('*Service') → get 'com.example.UserService' → use in other tools.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findType((String) args.get("pattern")));
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
                                "description", "Fully qualified class name (e.g., 'com.example.UserService')"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Method name to inspect, or '*' to list ALL methods in the class")),
                List.of("className", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_get_method_signature",
                "📋 SEE WHAT A METHOD DOES: Get parameter names, types, return type, exceptions. " +
                "USE CASE: Need to call a method but don't know its signature? This tells you! " +
                "TIP: Use methodName='*' to list ALL methods of a class - great for exploring unfamiliar code. " +
                "RETURNS: Parameter details you need for jdt_find_callers or writing code that calls this method.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getMethodSignature(
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
     * Tool: Find implementations of an interface or subclasses of a class.
     */
    public static ToolRegistration findImplementationsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of("typeName", Map.of(
                        "type", "string",
                        "description", "Fully qualified type name (e.g., 'com.example.UserRepository' or 'java.util.List')")),
                List.of("typeName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_implementations",
                "🔗 FIND ALL SUBCLASSES/IMPLEMENTATIONS: 'What classes implement UserRepository?' or 'What extends BaseController?' " +
                "USE CASE: Understanding polymorphism - you see 'UserRepository repo' but need to find the ACTUAL implementing class. " +
                "DIFFERENT FROM jdt_find_references: This finds HIERARCHY (who extends/implements), not USAGES (who calls/uses). " +
                "RESULT: List of concrete classes - useful for understanding the full inheritance tree.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findImplementations((String) args.get("typeName")));
    }

    private static CallToolResult findImplementations(String typeName) {
        try {
            IType type = findTypeByName(typeName);
            if (type == null) {
                return new CallToolResult("Type not found: " + typeName, true);
            }

            // Create type hierarchy
            ITypeHierarchy hierarchy = type.newTypeHierarchy(new NullProgressMonitor());

            List<Map<String, Object>> implementations = new ArrayList<>();

            // Get all subtypes (implementations for interfaces, subclasses for classes)
            IType[] subtypes = hierarchy.getAllSubtypes(type);
            for (IType subtype : subtypes) {
                Map<String, Object> implInfo = new HashMap<>();
                implInfo.put("simpleName", subtype.getElementName());
                implInfo.put("fullyQualifiedName", subtype.getFullyQualifiedName());
                implInfo.put("packageName", subtype.getPackageFragment().getElementName());

                try {
                    implInfo.put("isClass", subtype.isClass());
                    implInfo.put("isInterface", subtype.isInterface());
                    implInfo.put("isAbstract", org.eclipse.jdt.core.Flags.isAbstract(subtype.getFlags()));
                } catch (Exception e) {
                    // Ignore
                }

                if (subtype.getResource() != null) {
                    implInfo.put("file", subtype.getResource().getLocation().toString());
                }

                // Get direct supertype info
                String superclass = subtype.getSuperclassName();
                if (superclass != null) {
                    implInfo.put("superclass", superclass);
                }
                String[] interfaces = subtype.getSuperInterfaceNames();
                if (interfaces != null && interfaces.length > 0) {
                    implInfo.put("interfaces", List.of(interfaces));
                }

                implementations.add(implInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("typeName", typeName);
            result.put("isInterface", type.isInterface());
            result.put("implementationCount", implementations.size());
            result.put("implementations", implementations);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error finding implementations: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Find all callers/references to a method.
     */
    public static ToolRegistration findCallersTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name containing the method (e.g., 'com.example.UserService')"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Method name to find callers for (e.g., 'findById')")),
                List.of("className", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_find_callers",
                "📞 WHO CALLS THIS METHOD? Find all places in the codebase that call a specific method. " +
                "USE CASE: Before changing a method, know who depends on it! 'If I change saveUser(), what breaks?' " +
                "RETURNS: Class name, method name, file, line - so you can check/update each caller. " +
                "WORKFLOW: Want to change API? → jdt_find_callers → see all 15 callers → update them or reconsider the change.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> findCallers(
                (String) args.get("className"),
                (String) args.get("methodName")));
    }

    private static CallToolResult findCallers(String className, String methodName) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            // Find the method
            IMethod targetMethod = null;
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(methodName)) {
                    targetMethod = method;
                    break;
                }
            }

            if (targetMethod == null) {
                return new CallToolResult("Method not found: " + methodName + " in " + className, true);
            }

            List<Map<String, Object>> callers = new ArrayList<>();

            // Search for references across all projects
            SearchEngine engine = new SearchEngine();
            SearchPattern pattern = SearchPattern.createPattern(
                    targetMethod,
                    IJavaSearchConstants.REFERENCES);

            IJavaProject[] allProjects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects();
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(allProjects);

            SearchRequestor requestor = new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    Map<String, Object> callerInfo = new HashMap<>();

                    Object element = match.getElement();
                    if (element instanceof IMember member) {
                        callerInfo.put("callerType", member.getDeclaringType().getFullyQualifiedName());
                        callerInfo.put("callerMember", member.getElementName());

                        if (member instanceof IMethod m) {
                            callerInfo.put("callerKind", "method");
                            try {
                                callerInfo.put("callerSignature", m.getSignature());
                            } catch (Exception e) {
                                // Ignore
                            }
                        } else {
                            callerInfo.put("callerKind", "field_or_initializer");
                        }
                    }

                    if (match.getResource() != null) {
                        callerInfo.put("file", match.getResource().getLocation().toString());
                        callerInfo.put("offset", match.getOffset());
                        callerInfo.put("length", match.getLength());
                    }

                    callers.add(callerInfo);
                }
            };

            engine.search(
                    pattern,
                    new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                    scope,
                    requestor,
                    new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("methodName", methodName);
            result.put("callerCount", callers.size());
            result.put("callers", callers);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error finding callers: " + e.getMessage(), true);
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
