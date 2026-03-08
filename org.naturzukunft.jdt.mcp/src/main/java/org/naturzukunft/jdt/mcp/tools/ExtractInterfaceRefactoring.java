package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractInterfaceProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tool for extracting interfaces from classes.
 * Uses JDT's {@link ExtractInterfaceProcessor} for robust, cross-project-aware refactoring
 * that correctly handles generics, annotations, and complex class declarations.
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

            // Collect public non-static, non-constructor methods to extract
            List<IMethod> methodsToExtract = new java.util.ArrayList<>();
            boolean includeAll = methodNames.contains("*");

            for (IMethod method : type.getMethods()) {
                if (includeAll || methodNames.contains(method.getElementName())) {
                    int flags = method.getFlags();
                    if (Flags.isPublic(flags) && !Flags.isStatic(flags) && !method.isConstructor()) {
                        methodsToExtract.add(method);
                    }
                }
            }

            if (methodsToExtract.isEmpty()) {
                return new CallToolResult("No public methods found to extract", true);
            }

            // Use JDT's ExtractInterfaceProcessor
            CodeGenerationSettings settings = getCodeGenerationSettings(type);
            ExtractInterfaceProcessor processor = new ExtractInterfaceProcessor(type, settings);
            processor.setTypeName(interfaceName);
            processor.setExtractedMembers(methodsToExtract.toArray(new IMember[0]));
            processor.setReplace(true);
            processor.setAnnotations(false);

            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
            RefactoringStatus status = refactoring.checkAllConditions(new NullProgressMonitor());

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("interfaceName", interfaceName);
            result.put("methodCount", methodsToExtract.size());
            result.put("methods", methodsToExtract.stream().map(IMethod::getElementName).toList());
            String packageName = type.getPackageFragment().getElementName();
            result.put("newInterface", packageName.isEmpty() ? interfaceName : packageName + "." + interfaceName);

            List<String> realErrors = RefactoringSupport.getRealErrors(status);
            if (!realErrors.isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Extract interface has errors: " + String.join("; ", realErrors));
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            if (previewOnly) {
                Change change = refactoring.createChange(new NullProgressMonitor());
                result.put("status", "PREVIEW");
                result.put("message", "Preview of interface extraction (no changes applied)");
                result.put("changes", RefactoringSupport.describeChange(change));
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            Change change = refactoring.createChange(new NullProgressMonitor());
            change.perform(new NullProgressMonitor());

            result.put("status", "SUCCESS");
            result.put("message", "Interface extracted successfully");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("extract interface", e);
        }
    }

    private static CodeGenerationSettings getCodeGenerationSettings(IType type) {
        try {
            return org.eclipse.jdt.internal.corext.codemanipulation.JavaPreferencesSettings
                    .getCodeGenerationSettings(type.getJavaProject());
        } catch (Exception e) {
            // Headless fallback: ProjectScope.getNode() may throw IAE
            CodeGenerationSettings settings = new CodeGenerationSettings();
            settings.createComments = false;
            settings.tabWidth = 4;
            return settings;
        }
    }
}
