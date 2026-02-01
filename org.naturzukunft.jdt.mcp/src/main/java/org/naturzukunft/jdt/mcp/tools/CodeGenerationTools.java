package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for generating Java code (getters, setters, constructors, etc.).
 */
@SuppressWarnings("restriction")
public class CodeGenerationTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Add a method to a class.
     */
    public static ToolRegistration addMethodTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.UserService')"),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Name of the method to add (e.g., 'calculateTotal')"),
                        "returnType", Map.of(
                                "type", "string",
                                "description", "Return type (e.g., 'void', 'String', 'List<User>'). Default: 'void'"),
                        "visibility", Map.of(
                                "type", "string",
                                "description", "Visibility: 'public', 'protected', 'private', or 'package' (default: 'public')"),
                        "parameters", Map.of(
                                "type", "string",
                                "description", "Method parameters as comma-separated 'Type name' pairs (e.g., 'String id, User user')"),
                        "body", Map.of(
                                "type", "string",
                                "description", "Method body WITHOUT braces. Use \\n for newlines. (e.g., 'return user.getName();')"),
                        "annotations", Map.of(
                                "type", "string",
                                "description", "Comma-separated annotations (e.g., '@Override, @Deprecated')"),
                        "isStatic", Map.of(
                                "type", "boolean",
                                "description", "Make method static (default: false)"),
                        "isFinal", Map.of(
                                "type", "boolean",
                                "description", "Make method final (default: false)"),
                        "throwsClause", Map.of(
                                "type", "string",
                                "description", "Comma-separated exception types (e.g., 'IOException, SQLException')")),
                List.of("className", "methodName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_add_method",
                "Add a new method to an existing class. " +
                "🤖 PREFERRED over Edit tool for adding methods - guarantees correct syntax and placement. " +
                "Automatically places method after existing methods. " +
                "Use jdt_organize_imports afterwards if the method uses new types.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> addMethod(
                (String) args.get("className"),
                (String) args.get("methodName"),
                (String) args.get("returnType"),
                (String) args.get("visibility"),
                (String) args.get("parameters"),
                (String) args.get("body"),
                (String) args.get("annotations"),
                args.get("isStatic") != null ? (Boolean) args.get("isStatic") : false,
                args.get("isFinal") != null ? (Boolean) args.get("isFinal") : false,
                (String) args.get("throwsClause")));
    }

    private static CallToolResult addMethod(String className, String methodName, String returnType,
            String visibility, String parameters, String body, String annotations,
            boolean isStatic, boolean isFinal, String throwsClause) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type (binary or read-only): " + className, true);
            }

            // Check if method already exists (simple check by name)
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(methodName)) {
                    // Check parameter count for basic duplicate detection
                    int existingParamCount = method.getParameterNames().length;
                    int newParamCount = (parameters == null || parameters.trim().isEmpty()) ? 0 :
                            parameters.split(",").length;
                    if (existingParamCount == newParamCount) {
                        return new CallToolResult("Method '" + methodName + "' with " +
                                newParamCount + " parameters already exists in " + className, true);
                    }
                }
            }

            // Build the method source
            StringBuilder methodSource = new StringBuilder();
            methodSource.append("\n");

            // Annotations
            if (annotations != null && !annotations.trim().isEmpty()) {
                for (String annotation : annotations.split(",")) {
                    String ann = annotation.trim();
                    if (!ann.startsWith("@")) {
                        ann = "@" + ann;
                    }
                    methodSource.append("    ").append(ann).append("\n");
                }
            }

            // Method signature
            methodSource.append("    ");

            // Visibility
            String vis = (visibility == null || visibility.trim().isEmpty()) ? "public" : visibility.trim().toLowerCase();
            if (!vis.equals("package")) {
                methodSource.append(vis).append(" ");
            }

            // Static modifier
            if (isStatic) {
                methodSource.append("static ");
            }

            // Final modifier
            if (isFinal) {
                methodSource.append("final ");
            }

            // Return type
            String retType = (returnType == null || returnType.trim().isEmpty()) ? "void" : returnType.trim();
            methodSource.append(retType).append(" ");

            // Method name
            methodSource.append(methodName).append("(");

            // Parameters
            if (parameters != null && !parameters.trim().isEmpty()) {
                methodSource.append(parameters.trim());
            }
            methodSource.append(")");

            // Throws clause
            if (throwsClause != null && !throwsClause.trim().isEmpty()) {
                methodSource.append(" throws ").append(throwsClause.trim());
            }

            methodSource.append(" {\n");

            // Body
            if (body != null && !body.trim().isEmpty()) {
                // Handle escaped newlines and format body
                String[] lines = body.replace("\\n", "\n").split("\n");
                for (String line : lines) {
                    methodSource.append("        ").append(line.trim()).append("\n");
                }
            }

            methodSource.append("    }\n");

            // Insert method before the closing brace of the class
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                String newSource = source.substring(0, lastBrace) + methodSource.toString() + "\n" + source.substring(lastBrace);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            }

            // Build result
            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("methodName", methodName);
            result.put("signature", buildSignatureDescription(vis, isStatic, isFinal, retType, methodName, parameters, throwsClause));
            result.put("hint", "Use jdt_organize_imports to add missing imports if needed");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error adding method: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error adding method: " + e.getMessage(), true);
            }
        }
    }

    private static String buildSignatureDescription(String visibility, boolean isStatic, boolean isFinal,
            String returnType, String methodName, String parameters, String throwsClause) {
        StringBuilder sig = new StringBuilder();
        if (!"package".equals(visibility)) {
            sig.append(visibility).append(" ");
        }
        if (isStatic) sig.append("static ");
        if (isFinal) sig.append("final ");
        sig.append(returnType).append(" ").append(methodName).append("(");
        if (parameters != null && !parameters.trim().isEmpty()) {
            sig.append(parameters.trim());
        }
        sig.append(")");
        if (throwsClause != null && !throwsClause.trim().isEmpty()) {
            sig.append(" throws ").append(throwsClause.trim());
        }
        return sig.toString();
    }

    /**
     * Tool: Add a field to a class.
     */
    public static ToolRegistration addFieldTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.UserService')"),
                        "fieldName", Map.of(
                                "type", "string",
                                "description", "Name of the field to add (e.g., 'userRepository')"),
                        "fieldType", Map.of(
                                "type", "string",
                                "description", "Type of the field (e.g., 'String', 'List<User>', 'UserRepository')"),
                        "visibility", Map.of(
                                "type", "string",
                                "description", "Visibility: 'public', 'protected', 'private', or 'package' (default: 'private')"),
                        "annotations", Map.of(
                                "type", "string",
                                "description", "Comma-separated annotations (e.g., '@Autowired', '@Inject, @Named(\"main\")')"),
                        "isStatic", Map.of(
                                "type", "boolean",
                                "description", "Make field static (default: false)"),
                        "isFinal", Map.of(
                                "type", "boolean",
                                "description", "Make field final (default: false)"),
                        "initialValue", Map.of(
                                "type", "string",
                                "description", "Initial value (e.g., 'new ArrayList<>()', '\"default\"', '42')")),
                List.of("className", "fieldName", "fieldType"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_add_field",
                "Add a new field to an existing class. " +
                "🤖 PREFERRED over Edit tool for adding fields - guarantees correct syntax and placement. " +
                "Fields are placed after existing fields, before constructors/methods. " +
                "Use jdt_organize_imports afterwards if the field type needs importing. " +
                "TIP: For Spring @Autowired with Lombok @RequiredArgsConstructor, use isFinal=true.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> addField(
                (String) args.get("className"),
                (String) args.get("fieldName"),
                (String) args.get("fieldType"),
                (String) args.get("visibility"),
                (String) args.get("annotations"),
                args.get("isStatic") != null ? (Boolean) args.get("isStatic") : false,
                args.get("isFinal") != null ? (Boolean) args.get("isFinal") : false,
                (String) args.get("initialValue")));
    }

    private static CallToolResult addField(String className, String fieldName, String fieldType,
            String visibility, String annotations, boolean isStatic, boolean isFinal, String initialValue) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type (binary or read-only): " + className, true);
            }

            // Check if field already exists
            IField existingField = type.getField(fieldName);
            if (existingField != null && existingField.exists()) {
                return new CallToolResult("Field '" + fieldName + "' already exists in " + className, true);
            }

            // Build the field source
            StringBuilder fieldSource = new StringBuilder();
            fieldSource.append("\n");

            // Annotations
            if (annotations != null && !annotations.trim().isEmpty()) {
                for (String annotation : annotations.split(",")) {
                    String ann = annotation.trim();
                    if (!ann.startsWith("@")) {
                        ann = "@" + ann;
                    }
                    fieldSource.append("    ").append(ann).append("\n");
                }
            }

            // Field declaration
            fieldSource.append("    ");

            // Visibility
            String vis = (visibility == null || visibility.trim().isEmpty()) ? "private" : visibility.trim().toLowerCase();
            if (!vis.equals("package")) {
                fieldSource.append(vis).append(" ");
            }

            // Static modifier
            if (isStatic) {
                fieldSource.append("static ");
            }

            // Final modifier
            if (isFinal) {
                fieldSource.append("final ");
            }

            // Type and name
            fieldSource.append(fieldType.trim()).append(" ").append(fieldName);

            // Initial value
            if (initialValue != null && !initialValue.trim().isEmpty()) {
                fieldSource.append(" = ").append(initialValue.trim());
            }

            fieldSource.append(";\n");

            // Find insertion position (after existing fields, before methods/constructors)
            String source = cu.getSource();
            int insertPos = findFieldInsertPosition(type, source);

            if (insertPos > 0) {
                String newSource = source.substring(0, insertPos) + fieldSource.toString() + source.substring(insertPos);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            } else {
                // Fallback: insert after class opening brace
                ISourceRange typeRange = type.getSourceRange();
                int classStart = typeRange.getOffset();
                int bracePos = source.indexOf('{', classStart);
                if (bracePos > 0) {
                    String newSource = source.substring(0, bracePos + 1) + "\n" + fieldSource.toString() + source.substring(bracePos + 1);
                    cu.getBuffer().setContents(newSource);
                    cu.save(new NullProgressMonitor(), true);
                } else {
                    return new CallToolResult("Cannot find insertion point in class " + className, true);
                }
            }

            // Build result
            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("fieldName", fieldName);
            result.put("fieldType", fieldType);
            result.put("declaration", buildFieldDescription(vis, isStatic, isFinal, fieldType, fieldName, initialValue));
            result.put("hint", "Use jdt_organize_imports to add missing imports if needed");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error adding field: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error adding field: " + e.getMessage(), true);
            }
        }
    }

    private static int findFieldInsertPosition(IType type, String source) {
        try {
            IField[] fields = type.getFields();
            if (fields.length > 0) {
                // Insert after the last field
                ISourceRange lastFieldRange = fields[fields.length - 1].getSourceRange();
                int endOfField = lastFieldRange.getOffset() + lastFieldRange.getLength();
                // Find end of line after field
                int lineEnd = source.indexOf('\n', endOfField);
                return lineEnd > 0 ? lineEnd + 1 : endOfField;
            }

            // No fields exist - insert after class opening brace
            ISourceRange typeRange = type.getSourceRange();
            int classStart = typeRange.getOffset();
            int bracePos = source.indexOf('{', classStart);
            if (bracePos > 0) {
                // Find end of line after brace
                int lineEnd = source.indexOf('\n', bracePos);
                return lineEnd > 0 ? lineEnd + 1 : bracePos + 1;
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    private static String buildFieldDescription(String visibility, boolean isStatic, boolean isFinal,
            String fieldType, String fieldName, String initialValue) {
        StringBuilder desc = new StringBuilder();
        if (!"package".equals(visibility)) {
            desc.append(visibility).append(" ");
        }
        if (isStatic) desc.append("static ");
        if (isFinal) desc.append("final ");
        desc.append(fieldType).append(" ").append(fieldName);
        if (initialValue != null && !initialValue.trim().isEmpty()) {
            desc.append(" = ").append(initialValue.trim());
        }
        return desc.toString();
    }

    /**
     * Tool: Add imports to a class.
     */
    public static ToolRegistration addImportTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.UserService')"),
                        "imports", Map.of(
                                "type", "string",
                                "description", "Comma-separated fully qualified type names to import (e.g., 'java.util.List, java.util.Optional, com.example.User')")),
                List.of("className", "imports"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_add_import",
                "Add import statements to a Java class. " +
                "🤖 PREFERRED over Edit tool for adding imports - places them correctly and avoids duplicates. " +
                "Skips imports that already exist. " +
                "TIP: For most cases, prefer jdt_organize_imports after adding methods/fields - it auto-adds needed imports. " +
                "Use this tool when you need to add specific imports proactively.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> addImport(
                (String) args.get("className"),
                (String) args.get("imports")));
    }

    private static CallToolResult addImport(String className, String imports) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type (binary or read-only): " + className, true);
            }

            if (imports == null || imports.trim().isEmpty()) {
                return new CallToolResult("No imports specified", true);
            }

            // Parse import list
            List<String> importsToAdd = new ArrayList<>();
            for (String imp : imports.split(",")) {
                String trimmed = imp.trim();
                if (!trimmed.isEmpty()) {
                    importsToAdd.add(trimmed);
                }
            }

            if (importsToAdd.isEmpty()) {
                return new CallToolResult("No valid imports specified", true);
            }

            // Get existing imports
            java.util.Set<String> existingImports = new java.util.HashSet<>();
            for (org.eclipse.jdt.core.IImportDeclaration existingImport : cu.getImports()) {
                existingImports.add(existingImport.getElementName());
            }

            // Filter out already existing imports
            List<String> newImports = new ArrayList<>();
            List<String> skippedImports = new ArrayList<>();
            for (String imp : importsToAdd) {
                if (existingImports.contains(imp)) {
                    skippedImports.add(imp);
                } else {
                    newImports.add(imp);
                }
            }

            if (newImports.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "SUCCESS");
                result.put("className", className);
                result.put("addedImports", List.of());
                result.put("skippedImports", skippedImports);
                result.put("message", "All imports already exist");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // Add imports using JDT API
            for (String imp : newImports) {
                cu.createImport(imp, null, new NullProgressMonitor());
            }
            cu.save(new NullProgressMonitor(), true);

            // Build result
            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("addedImports", newImports);
            result.put("skippedImports", skippedImports);
            result.put("totalAdded", newImports.size());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error adding imports: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error adding imports: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Implement an interface in a class.
     */
    public static ToolRegistration implementInterfaceTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name that should implement the interface (e.g., 'com.example.UserServiceImpl')"),
                        "interfaceName", Map.of(
                                "type", "string",
                                "description", "Fully qualified interface name to implement (e.g., 'com.example.UserService')"),
                        "generateMethodStubs", Map.of(
                                "type", "boolean",
                                "description", "Generate stub implementations for all interface methods (default: true)")),
                List.of("className", "interfaceName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_implement_interface",
                "Make a class implement an interface and generate method stubs. " +
                "🤖 PREFERRED over Edit tool - automatically adds 'implements' clause and generates all required method stubs. " +
                "Skips methods that already exist in the class. " +
                "Use jdt_organize_imports afterwards to add the interface import.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> implementInterface(
                (String) args.get("className"),
                (String) args.get("interfaceName"),
                args.get("generateMethodStubs") != null ? (Boolean) args.get("generateMethodStubs") : true));
    }

    private static CallToolResult implementInterface(String className, String interfaceName, boolean generateMethodStubs) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type (binary or read-only): " + className, true);
            }

            IType interfaceType = findTypeByName(interfaceName);
            if (interfaceType == null) {
                return new CallToolResult("Interface not found: " + interfaceName, true);
            }

            if (!interfaceType.isInterface()) {
                return new CallToolResult(interfaceName + " is not an interface", true);
            }

            // Check if interface is already implemented
            String[] existingSuperInterfaces = type.getSuperInterfaceNames();
            String simpleInterfaceName = interfaceType.getElementName();
            for (String existing : existingSuperInterfaces) {
                if (existing.equals(simpleInterfaceName) || existing.equals(interfaceName)) {
                    // Interface already implemented, just generate missing methods
                    if (generateMethodStubs) {
                        return generateMissingMethods(type, interfaceType, cu, className, interfaceName, true);
                    } else {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "SUCCESS");
                        result.put("className", className);
                        result.put("interfaceName", interfaceName);
                        result.put("message", "Interface already implemented");
                        result.put("implementsAdded", false);
                        result.put("methodsGenerated", List.of());
                        return new CallToolResult(MAPPER.writeValueAsString(result), false);
                    }
                }
            }

            // Add implements clause
            String source = cu.getSource();
            String newSource = addImplementsClause(source, type, simpleInterfaceName);

            if (newSource == null) {
                return new CallToolResult("Failed to add implements clause", true);
            }

            cu.getBuffer().setContents(newSource);
            cu.save(new NullProgressMonitor(), true);

            // Add import for the interface
            cu.createImport(interfaceName, null, new NullProgressMonitor());
            cu.save(new NullProgressMonitor(), true);

            // Generate method stubs
            if (generateMethodStubs) {
                return generateMissingMethods(type, interfaceType, cu, className, interfaceName, false);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("interfaceName", interfaceName);
            result.put("implementsAdded", true);
            result.put("methodsGenerated", List.of());
            result.put("hint", "Use jdt_organize_imports to clean up imports");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error implementing interface: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error implementing interface: " + e.getMessage(), true);
            }
        }
    }

    private static String addImplementsClause(String source, IType type, String interfaceName) {
        try {
            ISourceRange nameRange = type.getNameRange();
            int classNameEnd = nameRange.getOffset() + nameRange.getLength();

            // Find the opening brace of the class
            int bracePos = source.indexOf('{', classNameEnd);
            if (bracePos < 0) {
                return null;
            }

            // Get the text between class name and opening brace
            String between = source.substring(classNameEnd, bracePos);

            // Check if there's already an implements clause
            if (between.contains("implements")) {
                // Add to existing implements clause
                int implementsPos = between.indexOf("implements");
                int insertPos = classNameEnd + implementsPos + "implements".length();

                // Find where to insert (after "implements " and before next keyword or brace)
                String afterImplements = source.substring(insertPos, bracePos).trim();

                // Insert at the end of the implements list
                int endOfList = bracePos;
                // Go backwards to find the actual end of interface list
                while (endOfList > insertPos && Character.isWhitespace(source.charAt(endOfList - 1))) {
                    endOfList--;
                }

                return source.substring(0, endOfList) + ", " + interfaceName + source.substring(endOfList);
            } else {
                // Check if there's an extends clause
                int insertPos;
                if (between.contains("extends")) {
                    // Find end of extends clause - look for the class name after extends
                    int extendsPos = between.indexOf("extends");
                    int afterExtends = classNameEnd + extendsPos + "extends".length();

                    // Skip whitespace and find the superclass name
                    int i = afterExtends;
                    while (i < bracePos && Character.isWhitespace(source.charAt(i))) {
                        i++;
                    }
                    // Find end of superclass name (may include generics)
                    int genericDepth = 0;
                    while (i < bracePos) {
                        char c = source.charAt(i);
                        if (c == '<') genericDepth++;
                        else if (c == '>') genericDepth--;
                        else if (genericDepth == 0 && (Character.isWhitespace(c) || c == '{')) {
                            break;
                        }
                        i++;
                    }
                    insertPos = i;
                } else {
                    // No extends, insert after class name (and any type parameters)
                    // Check for type parameters
                    int i = classNameEnd;
                    if (i < bracePos && source.charAt(i) == '<') {
                        int genericDepth = 1;
                        i++;
                        while (i < bracePos && genericDepth > 0) {
                            if (source.charAt(i) == '<') genericDepth++;
                            else if (source.charAt(i) == '>') genericDepth--;
                            i++;
                        }
                    }
                    insertPos = i;
                }

                return source.substring(0, insertPos) + " implements " + interfaceName + source.substring(insertPos);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static CallToolResult generateMissingMethods(IType type, IType interfaceType,
            ICompilationUnit cu, String className, String interfaceName, boolean alreadyImplemented) {
        try {
            // Get all methods from the interface (including inherited)
            List<IMethod> interfaceMethods = new ArrayList<>();
            collectInterfaceMethods(interfaceType, interfaceMethods);

            // Get existing methods in the class
            java.util.Set<String> existingMethods = new java.util.HashSet<>();
            for (IMethod method : type.getMethods()) {
                existingMethods.add(getMethodKey(method));
            }

            // Find methods that need to be implemented
            List<IMethod> methodsToImplement = new ArrayList<>();
            for (IMethod method : interfaceMethods) {
                if (!existingMethods.contains(getMethodKey(method))) {
                    methodsToImplement.add(method);
                }
            }

            List<String> generatedMethods = new ArrayList<>();

            if (!methodsToImplement.isEmpty()) {
                StringBuilder methodsSource = new StringBuilder();

                for (IMethod method : methodsToImplement) {
                    String methodName = method.getElementName();
                    String returnType = Signature.toString(method.getReturnType());
                    String[] paramNames = method.getParameterNames();
                    String[] paramTypes = method.getParameterTypes();
                    String[] exceptions = method.getExceptionTypes();

                    methodsSource.append("\n    @Override\n");
                    methodsSource.append("    public ").append(returnType).append(" ").append(methodName).append("(");

                    // Parameters
                    for (int i = 0; i < paramNames.length; i++) {
                        if (i > 0) methodsSource.append(", ");
                        methodsSource.append(Signature.toString(paramTypes[i])).append(" ").append(paramNames[i]);
                    }
                    methodsSource.append(")");

                    // Throws clause
                    if (exceptions.length > 0) {
                        methodsSource.append(" throws ");
                        for (int i = 0; i < exceptions.length; i++) {
                            if (i > 0) methodsSource.append(", ");
                            methodsSource.append(Signature.toString(exceptions[i]));
                        }
                    }

                    methodsSource.append(" {\n");

                    // Generate default return statement
                    if ("void".equals(returnType)) {
                        methodsSource.append("        // TODO: Implement this method\n");
                    } else if (isPrimitive(returnType)) {
                        String defaultValue = getDefaultPrimitiveValue(returnType);
                        methodsSource.append("        // TODO: Implement this method\n");
                        methodsSource.append("        return ").append(defaultValue).append(";\n");
                    } else {
                        methodsSource.append("        // TODO: Implement this method\n");
                        methodsSource.append("        return null;\n");
                    }

                    methodsSource.append("    }\n");

                    // Build signature for result
                    StringBuilder sig = new StringBuilder(methodName).append("(");
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (i > 0) sig.append(", ");
                        sig.append(Signature.toString(paramTypes[i]));
                    }
                    sig.append(")");
                    generatedMethods.add(sig.toString());
                }

                // Insert methods before the closing brace
                String source = cu.getSource();
                int lastBrace = source.lastIndexOf('}');
                if (lastBrace > 0) {
                    String newSource = source.substring(0, lastBrace) + methodsSource.toString() + "\n" + source.substring(lastBrace);
                    cu.getBuffer().setContents(newSource);
                    cu.save(new NullProgressMonitor(), true);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("interfaceName", interfaceName);
            result.put("implementsAdded", !alreadyImplemented);
            result.put("methodsGenerated", generatedMethods);
            result.put("methodCount", generatedMethods.size());
            if (!generatedMethods.isEmpty()) {
                result.put("hint", "Generated methods contain TODO comments - implement the actual logic");
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error generating methods: " + e.getMessage(), true);
        }
    }

    private static void collectInterfaceMethods(IType interfaceType, List<IMethod> methods) {
        try {
            // Add methods from this interface
            for (IMethod method : interfaceType.getMethods()) {
                // Skip static and default methods (they don't need implementation)
                int flags = method.getFlags();
                if (!Flags.isStatic(flags) && !Flags.isDefaultMethod(flags)) {
                    methods.add(method);
                }
            }

            // Recursively collect from super interfaces
            String[] superInterfaces = interfaceType.getSuperInterfaceNames();
            for (String superInterface : superInterfaces) {
                String[][] resolved = interfaceType.resolveType(superInterface);
                if (resolved != null && resolved.length > 0) {
                    String fqn = resolved[0][0] + "." + resolved[0][1];
                    IType superType = findTypeByName(fqn);
                    if (superType != null) {
                        collectInterfaceMethods(superType, methods);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors in collection
        }
    }

    private static String getMethodKey(IMethod method) {
        try {
            StringBuilder key = new StringBuilder(method.getElementName());
            key.append("(");
            String[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) key.append(",");
                key.append(Signature.toString(paramTypes[i]));
            }
            key.append(")");
            return key.toString();
        } catch (Exception e) {
            return method.getElementName();
        }
    }

    private static String getDefaultPrimitiveValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            default -> "null";
        };
    }

    /**
     * Tool: Generate getters and setters for fields.
     */
    public static ToolRegistration generateGettersSettersTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.model.User'). Get from jdt_find_type."),
                        "fieldNames", Map.of(
                                "type", "string",
                                "description", "Comma-separated field names (e.g., 'name,email'). Omit to generate for ALL non-static fields."),
                        "generateGetters", Map.of(
                                "type", "boolean",
                                "description", "Generate getters (default: true)"),
                        "generateSetters", Map.of(
                                "type", "boolean",
                                "description", "Generate setters (default: true)")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_getters_setters",
                "Generate getter and/or setter methods for fields in an existing class. " +
                "Skips methods that already exist. Final fields get only getters. " +
                "Use jdt_parse_java_file first to see which fields exist.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateGettersSetters(
                (String) args.get("className"),
                (String) args.get("fieldNames"),
                args.get("generateGetters") != null ? (Boolean) args.get("generateGetters") : true,
                args.get("generateSetters") != null ? (Boolean) args.get("generateSetters") : true));
    }

    private static CallToolResult generateGettersSetters(String className, String fieldNames,
            boolean generateGetters, boolean generateSetters) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type: " + className, true);
            }

            // Get fields to process
            List<IField> fieldsToProcess = new ArrayList<>();
            if (fieldNames != null && !fieldNames.isEmpty()) {
                for (String fieldName : fieldNames.split(",")) {
                    IField field = type.getField(fieldName.trim());
                    if (field != null && field.exists()) {
                        fieldsToProcess.add(field);
                    }
                }
            } else {
                for (IField field : type.getFields()) {
                    // Skip static and final fields for setters
                    if (!Flags.isStatic(field.getFlags())) {
                        fieldsToProcess.add(field);
                    }
                }
            }

            if (fieldsToProcess.isEmpty()) {
                return new CallToolResult("No fields found to generate getters/setters for", true);
            }

            // Generate methods
            List<String> generatedMethods = new ArrayList<>();
            StringBuilder methodsToAdd = new StringBuilder();

            for (IField field : fieldsToProcess) {
                String fieldName = field.getElementName();
                String fieldType = Signature.toString(field.getTypeSignature());
                String capitalizedName = capitalize(fieldName);
                boolean isFinal = Flags.isFinal(field.getFlags());

                // Generate getter
                if (generateGetters) {
                    String getterName = (fieldType.equals("boolean") ? "is" : "get") + capitalizedName;
                    if (type.getMethod(getterName, new String[0]) == null) {
                        methodsToAdd.append("\n    public ").append(fieldType).append(" ")
                                .append(getterName).append("() {\n")
                                .append("        return this.").append(fieldName).append(";\n")
                                .append("    }\n");
                        generatedMethods.add(getterName + "()");
                    }
                }

                // Generate setter (skip for final fields)
                if (generateSetters && !isFinal) {
                    String setterName = "set" + capitalizedName;
                    String[] paramTypes = new String[] { field.getTypeSignature() };
                    if (type.getMethod(setterName, paramTypes) == null) {
                        methodsToAdd.append("\n    public void ").append(setterName)
                                .append("(").append(fieldType).append(" ").append(fieldName).append(") {\n")
                                .append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n")
                                .append("    }\n");
                        generatedMethods.add(setterName + "(" + fieldType + ")");
                    }
                }
            }

            if (methodsToAdd.length() == 0) {
                return new CallToolResult("All getters/setters already exist", false);
            }

            // Insert methods before the closing brace of the class
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                String newSource = source.substring(0, lastBrace) + methodsToAdd.toString() + "\n" + source.substring(lastBrace);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("generatedMethods", generatedMethods);
            result.put("methodCount", generatedMethods.size());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error generating getters/setters: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error generating getters/setters: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Generate constructor.
     */
    public static ToolRegistration generateConstructorTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.model.User')"),
                        "fieldNames", Map.of(
                                "type", "string",
                                "description", "Fields to include as constructor params (e.g., 'id,name'). Omit for all non-static fields."),
                        "generateNoArgs", Map.of(
                                "type", "boolean",
                                "description", "Also generate a no-argument constructor (default: false)")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_constructor",
                "Generate constructor(s) that initialize fields. " +
                "By default creates all-args constructor. Set generateNoArgs=true to also create empty constructor (useful for JPA/Jackson).",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateConstructor(
                (String) args.get("className"),
                (String) args.get("fieldNames"),
                args.get("generateNoArgs") != null ? (Boolean) args.get("generateNoArgs") : false));
    }

    private static CallToolResult generateConstructor(String className, String fieldNames, boolean generateNoArgs) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type: " + className, true);
            }

            // Get fields for constructor
            List<IField> fieldsForConstructor = new ArrayList<>();
            if (fieldNames != null && !fieldNames.isEmpty()) {
                for (String fieldName : fieldNames.split(",")) {
                    IField field = type.getField(fieldName.trim());
                    if (field != null && field.exists()) {
                        fieldsForConstructor.add(field);
                    }
                }
            } else {
                for (IField field : type.getFields()) {
                    if (!Flags.isStatic(field.getFlags())) {
                        fieldsForConstructor.add(field);
                    }
                }
            }

            List<String> generatedConstructors = new ArrayList<>();
            StringBuilder constructorsToAdd = new StringBuilder();

            // Generate no-args constructor if requested
            if (generateNoArgs) {
                boolean hasNoArgsConstructor = false;
                for (IMethod method : type.getMethods()) {
                    if (method.isConstructor() && method.getParameterNames().length == 0) {
                        hasNoArgsConstructor = true;
                        break;
                    }
                }
                if (!hasNoArgsConstructor) {
                    constructorsToAdd.append("\n    public ").append(type.getElementName()).append("() {\n")
                            .append("    }\n");
                    generatedConstructors.add(type.getElementName() + "()");
                }
            }

            // Generate all-args constructor
            if (!fieldsForConstructor.isEmpty()) {
                StringBuilder params = new StringBuilder();
                StringBuilder assignments = new StringBuilder();

                for (int i = 0; i < fieldsForConstructor.size(); i++) {
                    IField field = fieldsForConstructor.get(i);
                    String fieldName = field.getElementName();
                    String fieldType = Signature.toString(field.getTypeSignature());

                    if (i > 0) {
                        params.append(", ");
                    }
                    params.append(fieldType).append(" ").append(fieldName);
                    assignments.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
                }

                constructorsToAdd.append("\n    public ").append(type.getElementName())
                        .append("(").append(params).append(") {\n")
                        .append(assignments)
                        .append("    }\n");

                generatedConstructors.add(type.getElementName() + "(" + params + ")");
            }

            if (constructorsToAdd.length() == 0) {
                return new CallToolResult("No constructors to generate", false);
            }

            // Insert constructors
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                // Try to insert after field declarations
                int insertPos = findInsertPositionAfterFields(type, source);
                if (insertPos > 0 && insertPos < lastBrace) {
                    String newSource = source.substring(0, insertPos) + constructorsToAdd.toString() + source.substring(insertPos);
                    cu.getBuffer().setContents(newSource);
                } else {
                    String newSource = source.substring(0, lastBrace) + constructorsToAdd.toString() + "\n" + source.substring(lastBrace);
                    cu.getBuffer().setContents(newSource);
                }
                cu.save(new NullProgressMonitor(), true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("generatedConstructors", generatedConstructors);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error generating constructor: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error generating constructor: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Generate equals and hashCode methods.
     */
    public static ToolRegistration generateEqualsHashCodeTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.model.User')"),
                        "fieldNames", Map.of(
                                "type", "string",
                                "description", "Fields to use for equality comparison (e.g., 'id' or 'id,email'). Omit to use all non-static fields.")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_equals_hashcode",
                "Generate equals() and hashCode() methods using java.util.Objects. " +
                "IMPORTANT: Both methods must use the same fields. For entity classes, usually just 'id' is enough.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateEqualsHashCode(
                (String) args.get("className"),
                (String) args.get("fieldNames")));
    }

    private static CallToolResult generateEqualsHashCode(String className, String fieldNames) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type: " + className, true);
            }

            // Get fields for equals/hashCode
            List<IField> fieldsToUse = new ArrayList<>();
            if (fieldNames != null && !fieldNames.isEmpty()) {
                for (String fieldName : fieldNames.split(",")) {
                    IField field = type.getField(fieldName.trim());
                    if (field != null && field.exists()) {
                        fieldsToUse.add(field);
                    }
                }
            } else {
                for (IField field : type.getFields()) {
                    if (!Flags.isStatic(field.getFlags())) {
                        fieldsToUse.add(field);
                    }
                }
            }

            if (fieldsToUse.isEmpty()) {
                return new CallToolResult("No fields found for equals/hashCode generation", true);
            }

            List<String> generatedMethods = new ArrayList<>();
            StringBuilder methodsToAdd = new StringBuilder();

            String simpleClassName = type.getElementName();

            // Generate equals method
            methodsToAdd.append("\n    @Override\n")
                    .append("    public boolean equals(Object obj) {\n")
                    .append("        if (this == obj) return true;\n")
                    .append("        if (obj == null || getClass() != obj.getClass()) return false;\n")
                    .append("        ").append(simpleClassName).append(" other = (").append(simpleClassName).append(") obj;\n")
                    .append("        return ");

            for (int i = 0; i < fieldsToUse.size(); i++) {
                IField field = fieldsToUse.get(i);
                String fieldName = field.getElementName();
                String fieldType = Signature.toString(field.getTypeSignature());

                if (i > 0) {
                    methodsToAdd.append("\n            && ");
                }

                if (isPrimitive(fieldType)) {
                    methodsToAdd.append(fieldName).append(" == other.").append(fieldName);
                } else {
                    methodsToAdd.append("java.util.Objects.equals(").append(fieldName).append(", other.").append(fieldName).append(")");
                }
            }

            methodsToAdd.append(";\n    }\n");
            generatedMethods.add("equals(Object)");

            // Generate hashCode method
            methodsToAdd.append("\n    @Override\n")
                    .append("    public int hashCode() {\n")
                    .append("        return java.util.Objects.hash(");

            for (int i = 0; i < fieldsToUse.size(); i++) {
                if (i > 0) {
                    methodsToAdd.append(", ");
                }
                methodsToAdd.append(fieldsToUse.get(i).getElementName());
            }

            methodsToAdd.append(");\n    }\n");
            generatedMethods.add("hashCode()");

            // Insert methods
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                String newSource = source.substring(0, lastBrace) + methodsToAdd.toString() + "\n" + source.substring(lastBrace);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("generatedMethods", generatedMethods);
            result.put("fieldsUsed", fieldsToUse.stream().map(IField::getElementName).toList());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error generating equals/hashCode: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error generating equals/hashCode: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Generate toString method.
     */
    public static ToolRegistration generateToStringTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.model.User')"),
                        "fieldNames", Map.of(
                                "type", "string",
                                "description", "Fields to include in toString output (e.g., 'id,name'). Omit to include all non-static fields.")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_tostring",
                "Generate toString() method that returns a readable string like 'User{id=1, name='John'}'. " +
                "Useful for debugging and logging.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateToString(
                (String) args.get("className"),
                (String) args.get("fieldNames")));
    }

    private static CallToolResult generateToString(String className, String fieldNames) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type: " + className, true);
            }

            // Get fields for toString
            List<IField> fieldsToUse = new ArrayList<>();
            if (fieldNames != null && !fieldNames.isEmpty()) {
                for (String fieldName : fieldNames.split(",")) {
                    IField field = type.getField(fieldName.trim());
                    if (field != null && field.exists()) {
                        fieldsToUse.add(field);
                    }
                }
            } else {
                for (IField field : type.getFields()) {
                    if (!Flags.isStatic(field.getFlags())) {
                        fieldsToUse.add(field);
                    }
                }
            }

            String simpleClassName = type.getElementName();

            // Generate toString method
            StringBuilder method = new StringBuilder();
            method.append("\n    @Override\n")
                    .append("    public String toString() {\n")
                    .append("        return \"").append(simpleClassName).append("{\" +\n");

            for (int i = 0; i < fieldsToUse.size(); i++) {
                IField field = fieldsToUse.get(i);
                String fieldName = field.getElementName();
                String fieldType = Signature.toString(field.getTypeSignature());

                if (i == 0) {
                    method.append("            \"").append(fieldName).append("=");
                } else {
                    method.append("            \", ").append(fieldName).append("=");
                }

                if (fieldType.equals("String")) {
                    method.append("'\" + ").append(fieldName).append(" + \"'\" +\n");
                } else {
                    method.append("\" + ").append(fieldName).append(" +\n");
                }
            }

            method.append("            \"}\";\n")
                    .append("    }\n");

            // Insert method
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                String newSource = source.substring(0, lastBrace) + method.toString() + "\n" + source.substring(lastBrace);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("generatedMethod", "toString()");
            result.put("fieldsUsed", fieldsToUse.stream().map(IField::getElementName).toList());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error generating toString: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error generating toString: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Generate delegate methods.
     */
    public static ToolRegistration generateDelegateMethodsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified class name (e.g., 'com.example.UserServiceImpl')"),
                        "fieldName", Map.of(
                                "type", "string",
                                "description", "Field name to delegate to (e.g., 'delegate' or 'repository')"),
                        "methodNames", Map.of(
                                "type", "string",
                                "description", "Comma-separated method names to delegate (e.g., 'save,findById'). Omit for ALL public methods of the field's type.")),
                List.of("className", "fieldName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_generate_delegate_methods",
                "Generate methods that DELEGATE to another object (the Delegation Pattern). " +
                "Example: If UserService has a 'UserRepository repository' field, this generates methods like " +
                "'public User save(User u) { return repository.save(u); }'. " +
                "Use when wrapping or decorating another class.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> generateDelegateMethods(
                (String) args.get("className"),
                (String) args.get("fieldName"),
                (String) args.get("methodNames")));
    }

    private static CallToolResult generateDelegateMethods(String className, String fieldName, String methodNames) {
        try {
            IType type = findTypeByName(className);
            if (type == null) {
                return new CallToolResult("Type not found: " + className, true);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return new CallToolResult("Cannot modify type: " + className, true);
            }

            // Get the field
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return new CallToolResult("Field not found: " + fieldName + " in " + className, true);
            }

            // Get the field's type
            String fieldTypeSig = field.getTypeSignature();
            String fieldTypeName = Signature.toString(fieldTypeSig);

            // Resolve the field type
            String[][] resolvedType = type.resolveType(fieldTypeName);
            if (resolvedType == null || resolvedType.length == 0) {
                return new CallToolResult("Cannot resolve type: " + fieldTypeName, true);
            }

            String fullyQualifiedFieldType = resolvedType[0][0] + "." + resolvedType[0][1];
            IType fieldType = findTypeByName(fullyQualifiedFieldType);

            if (fieldType == null) {
                return new CallToolResult("Field type not found: " + fullyQualifiedFieldType, true);
            }

            // Determine which methods to delegate
            List<IMethod> methodsToDelegate = new java.util.ArrayList<>();
            java.util.Set<String> targetMethodNames = null;

            if (methodNames != null && !methodNames.isEmpty()) {
                targetMethodNames = new java.util.HashSet<>();
                for (String name : methodNames.split(",")) {
                    targetMethodNames.add(name.trim());
                }
            }

            for (IMethod method : fieldType.getMethods()) {
                // Only public, non-static, non-constructor methods
                int flags = method.getFlags();
                if (Flags.isPublic(flags) && !Flags.isStatic(flags) && !method.isConstructor()) {
                    if (targetMethodNames == null || targetMethodNames.contains(method.getElementName())) {
                        // Check if method already exists in the class
                        IMethod existing = type.getMethod(method.getElementName(), method.getParameterTypes());
                        if (existing == null || !existing.exists()) {
                            methodsToDelegate.add(method);
                        }
                    }
                }
            }

            if (methodsToDelegate.isEmpty()) {
                return new CallToolResult("No methods to delegate (they may already exist)", false);
            }

            // Generate delegate methods
            List<String> generatedMethods = new java.util.ArrayList<>();
            StringBuilder methodsToAdd = new StringBuilder();

            for (IMethod method : methodsToDelegate) {
                String methodName = method.getElementName();
                String returnType = Signature.toString(method.getReturnType());
                boolean hasReturn = !"void".equals(returnType);

                String[] paramNames = method.getParameterNames();
                String[] paramTypes = method.getParameterTypes();

                // Build parameter list
                StringBuilder params = new StringBuilder();
                StringBuilder args = new StringBuilder();
                for (int i = 0; i < paramNames.length; i++) {
                    if (i > 0) {
                        params.append(", ");
                        args.append(", ");
                    }
                    params.append(Signature.toString(paramTypes[i])).append(" ").append(paramNames[i]);
                    args.append(paramNames[i]);
                }

                // Build throws clause
                StringBuilder throwsClause = new StringBuilder();
                String[] exceptions = method.getExceptionTypes();
                if (exceptions.length > 0) {
                    throwsClause.append(" throws ");
                    for (int i = 0; i < exceptions.length; i++) {
                        if (i > 0) throwsClause.append(", ");
                        throwsClause.append(Signature.toString(exceptions[i]));
                    }
                }

                methodsToAdd.append("\n    public ").append(returnType).append(" ")
                        .append(methodName).append("(").append(params).append(")")
                        .append(throwsClause).append(" {\n")
                        .append("        ").append(hasReturn ? "return " : "")
                        .append(fieldName).append(".").append(methodName).append("(").append(args).append(");\n")
                        .append("    }\n");

                generatedMethods.add(methodName + "(" + params + ")");
            }

            // Insert methods
            String source = cu.getSource();
            int lastBrace = source.lastIndexOf('}');
            if (lastBrace > 0) {
                String newSource = source.substring(0, lastBrace) + methodsToAdd.toString() + "\n" + source.substring(lastBrace);
                cu.getBuffer().setContents(newSource);
                cu.save(new NullProgressMonitor(), true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("className", className);
            result.put("delegateField", fieldName);
            result.put("delegateType", fullyQualifiedFieldType);
            result.put("generatedMethods", generatedMethods);
            result.put("methodCount", generatedMethods.size());

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error generating delegate methods: " + e.getMessage(), true);
        }
    }

    /**
     * Helper: Capitalize first letter.
     */
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Helper: Check if type is primitive.
     */
    private static boolean isPrimitive(String type) {
        return type.equals("int") || type.equals("long") || type.equals("short") ||
                type.equals("byte") || type.equals("float") || type.equals("double") ||
                type.equals("boolean") || type.equals("char");
    }

    /**
     * Helper: Find position after field declarations.
     */
    private static int findInsertPositionAfterFields(IType type, String source) {
        try {
            IField[] fields = type.getFields();
            if (fields.length > 0) {
                ISourceRange lastFieldRange = fields[fields.length - 1].getSourceRange();
                return lastFieldRange.getOffset() + lastFieldRange.getLength() + 1;
            }
            // If no fields, insert after class declaration opening brace
            ISourceRange typeRange = type.getSourceRange();
            int classStart = typeRange.getOffset();
            int bracePos = source.indexOf('{', classStart);
            if (bracePos > 0) {
                return bracePos + 1;
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
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
