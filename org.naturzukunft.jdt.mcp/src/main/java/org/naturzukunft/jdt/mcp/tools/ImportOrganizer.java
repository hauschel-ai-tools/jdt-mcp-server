package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.text.edits.TextEdit;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tool for organizing Java imports.
 * Uses JDT's {@link OrganizeImportsOperation} for robust import handling
 * including correct star-import expansion, static imports, and sorting.
 */
class ImportOrganizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImportOrganizer() {
        // utility class
    }

    static ToolRegistration organizeImportsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to .java file (e.g., '/home/user/project/src/main/java/com/example/MyClass.java')"),
                        "removeUnused", Map.of(
                                "type", "boolean",
                                "description", "Remove imports that are not used in the code (default: true)")),
                List.of("filePath"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_organize_imports",
                "Clean up imports in a Java file: removes unused imports and sorts them. " +
                "TIP: Call jdt_refresh_project first if you modified the file externally.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> organizeImports(
                (String) args.get("filePath"),
                args.get("removeUnused") != null ? (Boolean) args.get("removeUnused") : true));
    }

    private static CallToolResult organizeImports(String filePath, boolean removeUnused) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Capture imports before
            List<String> importsBefore = new java.util.ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                importsBefore.add(imp.getElementName());
            }

            // Parse AST for OrganizeImportsOperation
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            // For ambiguous imports: pick first match (headless, no UI dialog)
            OrganizeImportsOperation.IChooseImportQuery chooseQuery = (openChoices, ranges) -> {
                TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                for (int i = 0; i < openChoices.length; i++) {
                    result[i] = openChoices[i].length > 0 ? openChoices[i][0] : null;
                }
                return result;
            };

            OrganizeImportsOperation op = new OrganizeImportsOperation(
                    cu, ast, true, removeUnused, true, chooseQuery);
            TextEdit edit = op.createTextEdit(new NullProgressMonitor());

            // Determine removed imports for reporting
            List<String> toRemove = new java.util.ArrayList<>();
            if (edit != null && edit.hasChildren()) {
                // Apply the edit
                ICompilationUnit workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
                try {
                    workingCopy.applyTextEdit(edit, new NullProgressMonitor());
                    workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
                } finally {
                    workingCopy.discardWorkingCopy();
                }

                // Capture imports after to compute diff
                List<String> importsAfter = new java.util.ArrayList<>();
                for (IImportDeclaration imp : cu.getImports()) {
                    importsAfter.add(imp.getElementName());
                }

                // Removed = before - after
                for (String imp : importsBefore) {
                    if (!importsAfter.contains(imp)) {
                        toRemove.add(imp);
                    }
                }

                Map<String, Object> result = new HashMap<>();
                result.put("status", "SUCCESS");
                result.put("filePath", filePath);
                result.put("importsBefore", importsBefore.size());
                result.put("importsAfter", importsAfter.size());
                result.put("removedImports", toRemove);
                result.put("message",
                        "Organize imports completed - removed " + toRemove.size() + " unused imports");

                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // No changes needed
            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("filePath", filePath);
            result.put("importsBefore", importsBefore.size());
            result.put("importsAfter", importsBefore.size());
            result.put("removedImports", List.of());
            result.put("message", "Organize imports completed - no changes needed");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("organize imports", e);
        }
    }
}
