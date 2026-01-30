package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for creating Java code elements.
 */
public class CreationTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool: Create a new Java class.
     */
    public static ToolRegistration createClassTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "packageName", Map.of(
                                "type", "string",
                                "description", "Package where class will be created (e.g., 'com.example.model'). Creates package if it doesn't exist."),
                        "className", Map.of(
                                "type", "string",
                                "description", "Simple class name WITHOUT package (e.g., 'User', not 'com.example.User')"),
                        "superclass", Map.of(
                                "type", "string",
                                "description", "Superclass fully qualified name (optional)"),
                        "interfaces", Map.of(
                                "type", "string",
                                "description", "Comma-separated list of interface names to implement (optional)"),
                        "isAbstract", Map.of(
                                "type", "boolean",
                                "description", "Make the class abstract (default: false)"),
                        "sourceFolder", Map.of(
                                "type", "string",
                                "description", "Source folder path (optional, defaults to first source folder)")),
                List.of("projectName", "packageName", "className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_create_class",
                "Create a new Java class file. Creates the package if needed. " +
                "Returns the file path of the created class. " +
                "NOTE: Eclipse auto-detects this change, no refresh needed.",
                schema,
                null);

        return new ToolRegistration(tool, args -> createClass(
                (String) args.get("projectName"),
                (String) args.get("packageName"),
                (String) args.get("className"),
                (String) args.get("superclass"),
                (String) args.get("interfaces"),
                args.get("isAbstract") != null ? (Boolean) args.get("isAbstract") : false,
                (String) args.get("sourceFolder")));
    }

    private static CallToolResult createClass(String projectName, String packageName, String className,
            String superclass, String interfaces, boolean isAbstract, String sourceFolder) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            // Find source folder
            IPackageFragmentRoot sourceRoot = findSourceRoot(javaProject, sourceFolder);
            if (sourceRoot == null) {
                return new CallToolResult("No source folder found in project: " + projectName, true);
            }

            // Create or get package
            IPackageFragment pkg = sourceRoot.createPackageFragment(
                    packageName, true, new NullProgressMonitor());

            // Build class source
            StringBuilder source = new StringBuilder();
            source.append("package ").append(packageName).append(";\n\n");

            // Class declaration
            source.append("public ");
            if (isAbstract) {
                source.append("abstract ");
            }
            source.append("class ").append(className);

            // Superclass
            if (superclass != null && !superclass.isEmpty()) {
                source.append(" extends ").append(superclass);
            }

            // Interfaces
            if (interfaces != null && !interfaces.isEmpty()) {
                source.append(" implements ");
                source.append(interfaces.replace(",", ", ").trim());
            }

            source.append(" {\n\n");
            source.append("}\n");

            // Create compilation unit
            String fileName = className + ".java";
            ICompilationUnit cu = pkg.createCompilationUnit(
                    fileName, source.toString(), true, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("projectName", projectName);
            result.put("packageName", packageName);
            result.put("className", className);
            result.put("fullyQualifiedName", packageName + "." + className);
            if (cu.getResource() != null) {
                result.put("file", cu.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error creating class: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error creating class: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Create a new Java interface.
     */
    public static ToolRegistration createInterfaceTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "packageName", Map.of(
                                "type", "string",
                                "description", "Package where interface will be created (e.g., 'com.example.service')"),
                        "interfaceName", Map.of(
                                "type", "string",
                                "description", "Simple interface name WITHOUT package (e.g., 'UserService')"),
                        "superInterfaces", Map.of(
                                "type", "string",
                                "description", "Comma-separated list of super interface names (optional)"),
                        "sourceFolder", Map.of(
                                "type", "string",
                                "description", "Source folder path (optional, defaults to first source folder)")),
                List.of("projectName", "packageName", "interfaceName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_create_interface",
                "Create a new Java interface file. Creates the package if needed. " +
                "Returns the file path of the created interface.",
                schema,
                null);

        return new ToolRegistration(tool, args -> createInterface(
                (String) args.get("projectName"),
                (String) args.get("packageName"),
                (String) args.get("interfaceName"),
                (String) args.get("superInterfaces"),
                (String) args.get("sourceFolder")));
    }

    private static CallToolResult createInterface(String projectName, String packageName, String interfaceName,
            String superInterfaces, String sourceFolder) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            // Find source folder
            IPackageFragmentRoot sourceRoot = findSourceRoot(javaProject, sourceFolder);
            if (sourceRoot == null) {
                return new CallToolResult("No source folder found in project: " + projectName, true);
            }

            // Create or get package
            IPackageFragment pkg = sourceRoot.createPackageFragment(
                    packageName, true, new NullProgressMonitor());

            // Build interface source
            StringBuilder source = new StringBuilder();
            source.append("package ").append(packageName).append(";\n\n");

            // Interface declaration
            source.append("public interface ").append(interfaceName);

            // Super interfaces
            if (superInterfaces != null && !superInterfaces.isEmpty()) {
                source.append(" extends ");
                source.append(superInterfaces.replace(",", ", ").trim());
            }

            source.append(" {\n\n");
            source.append("}\n");

            // Create compilation unit
            String fileName = interfaceName + ".java";
            ICompilationUnit cu = pkg.createCompilationUnit(
                    fileName, source.toString(), true, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("projectName", projectName);
            result.put("packageName", packageName);
            result.put("interfaceName", interfaceName);
            result.put("fullyQualifiedName", packageName + "." + interfaceName);
            if (cu.getResource() != null) {
                result.put("file", cu.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error creating interface: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error creating interface: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Create a new Java enum.
     */
    public static ToolRegistration createEnumTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "packageName", Map.of(
                                "type", "string",
                                "description", "Package where enum will be created (e.g., 'com.example.model')"),
                        "enumName", Map.of(
                                "type", "string",
                                "description", "Simple enum name WITHOUT package (e.g., 'Status')"),
                        "values", Map.of(
                                "type", "string",
                                "description", "Comma-separated enum constants (e.g., 'ACTIVE, INACTIVE, PENDING')"),
                        "interfaces", Map.of(
                                "type", "string",
                                "description", "Comma-separated list of interface names to implement (optional)"),
                        "sourceFolder", Map.of(
                                "type", "string",
                                "description", "Source folder path (optional, defaults to first source folder)")),
                List.of("projectName", "packageName", "enumName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_create_enum",
                "Create a new Java enum file with the specified constants. Creates the package if needed. " +
                "Returns the file path of the created enum.",
                schema,
                null);

        return new ToolRegistration(tool, args -> createEnum(
                (String) args.get("projectName"),
                (String) args.get("packageName"),
                (String) args.get("enumName"),
                (String) args.get("values"),
                (String) args.get("interfaces"),
                (String) args.get("sourceFolder")));
    }

    private static CallToolResult createEnum(String projectName, String packageName, String enumName,
            String values, String interfaces, String sourceFolder) {
        try {
            IJavaProject javaProject = getJavaProject(projectName);
            if (javaProject == null) {
                return new CallToolResult("Java project not found: " + projectName, true);
            }

            // Find source folder
            IPackageFragmentRoot sourceRoot = findSourceRoot(javaProject, sourceFolder);
            if (sourceRoot == null) {
                return new CallToolResult("No source folder found in project: " + projectName, true);
            }

            // Create or get package
            IPackageFragment pkg = sourceRoot.createPackageFragment(
                    packageName, true, new NullProgressMonitor());

            // Build enum source
            StringBuilder source = new StringBuilder();
            source.append("package ").append(packageName).append(";\n\n");

            // Enum declaration
            source.append("public enum ").append(enumName);

            // Interfaces
            if (interfaces != null && !interfaces.isEmpty()) {
                source.append(" implements ");
                source.append(interfaces.replace(",", ", ").trim());
            }

            source.append(" {\n");

            // Enum values
            if (values != null && !values.isEmpty()) {
                String[] valueArray = values.split(",");
                for (int i = 0; i < valueArray.length; i++) {
                    source.append("    ").append(valueArray[i].trim());
                    if (i < valueArray.length - 1) {
                        source.append(",");
                    } else {
                        source.append(";");
                    }
                    source.append("\n");
                }
            }

            source.append("}\n");

            // Create compilation unit
            String fileName = enumName + ".java";
            ICompilationUnit cu = pkg.createCompilationUnit(
                    fileName, source.toString(), true, new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("projectName", projectName);
            result.put("packageName", packageName);
            result.put("enumName", enumName);
            result.put("fullyQualifiedName", packageName + "." + enumName);
            if (cu.getResource() != null) {
                result.put("file", cu.getResource().getLocation().toString());
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error creating enum: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error creating enum: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Helper: Get IJavaProject by name.
     */
    private static IJavaProject getJavaProject(String projectName) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists() && project.isOpen()
                    && project.hasNature(JavaCore.NATURE_ID)) {
                return JavaCore.create(project);
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error getting Java project: " + e.getMessage());
        }
        return null;
    }

    /**
     * Helper: Find source folder in project.
     */
    private static IPackageFragmentRoot findSourceRoot(IJavaProject project, String sourceFolder) {
        try {
            for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    if (sourceFolder == null || sourceFolder.isEmpty()) {
                        return root; // Return first source folder
                    }
                    if (root.getPath().toString().contains(sourceFolder)) {
                        return root;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Error finding source folder: " + e.getMessage());
        }
        return null;
    }
}
