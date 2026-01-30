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

        return new ToolRegistration(tool, args -> generateGettersSetters(
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

        return new ToolRegistration(tool, args -> generateConstructor(
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

        return new ToolRegistration(tool, args -> generateEqualsHashCode(
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

        return new ToolRegistration(tool, args -> generateToString(
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

        return new ToolRegistration(tool, args -> generateDelegateMethods(
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
