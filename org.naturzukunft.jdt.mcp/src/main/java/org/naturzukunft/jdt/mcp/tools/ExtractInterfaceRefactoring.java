package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tool for extracting interfaces from classes.
 *
 * Extracted from RefactoringTools as part of #30 (God Class refactoring).
 */
class ExtractInterfaceRefactoring {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractInterfaceRefactoring() {
        // utility class
    }

    @SuppressWarnings("unchecked")
    static ToolRegistration extractInterfaceTool() {
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
            IType type = RefactoringSupport.findTypeInSourceProject(className);

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
}
