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
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tool for organizing Java imports.
 *
 * Extracted from RefactoringTools as part of #30 (God Class refactoring).
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

            // Get imports before
            List<String> importsBefore = new java.util.ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                importsBefore.add(imp.getElementName());
            }

            // Parse the compilation unit
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            // Collect used types and static members from AST
            java.util.Set<String> usedTypes = new java.util.HashSet<>();
            java.util.Set<String> usedStaticMembers = new java.util.HashSet<>();
            ast.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.SimpleName node) {
                    org.eclipse.jdt.core.dom.IBinding binding = node.resolveBinding();
                    if (binding instanceof org.eclipse.jdt.core.dom.ITypeBinding typeBinding) {
                        String qualifiedName = typeBinding.getQualifiedName();
                        if (qualifiedName != null && !qualifiedName.isEmpty() && qualifiedName.contains(".")) {
                            usedTypes.add(qualifiedName);
                        }
                    } else if (binding instanceof org.eclipse.jdt.core.dom.IMethodBinding methodBinding) {
                        // Track static method imports (e.g., assertThat from AssertJ)
                        if ((methodBinding.getModifiers() & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
                            org.eclipse.jdt.core.dom.ITypeBinding declaringClass = methodBinding.getDeclaringClass();
                            if (declaringClass != null) {
                                String staticImport = declaringClass.getQualifiedName() + "." + methodBinding.getName();
                                usedStaticMembers.add(staticImport);
                            }
                        }
                    } else if (binding instanceof org.eclipse.jdt.core.dom.IVariableBinding variableBinding) {
                        // Track static field imports (e.g., constants)
                        if ((variableBinding.getModifiers() & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
                            org.eclipse.jdt.core.dom.ITypeBinding declaringClass = variableBinding.getDeclaringClass();
                            if (declaringClass != null) {
                                String staticImport = declaringClass.getQualifiedName() + "." + variableBinding.getName();
                                usedStaticMembers.add(staticImport);
                            }
                        }
                    }
                    return true;
                }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.QualifiedName node) {
                    if (node.resolveBinding() instanceof org.eclipse.jdt.core.dom.ITypeBinding binding) {
                        String qualifiedName = binding.getQualifiedName();
                        if (qualifiedName != null && !qualifiedName.isEmpty()) {
                            usedTypes.add(qualifiedName);
                        }
                    }
                    return true;
                }
            });

            // Determine which imports to remove
            List<String> toRemove = new java.util.ArrayList<>();
            if (removeUnused) {
                for (IImportDeclaration imp : cu.getImports()) {
                    String importName = imp.getElementName();
                    boolean isUsed = false;
                    boolean isStatic = org.eclipse.jdt.core.Flags.isStatic(imp.getFlags());

                    if (imp.isOnDemand()) {
                        // Star imports - check if any type/member from that package is used
                        String packagePrefix = importName.substring(0, importName.length() - 1); // Remove *
                        if (isStatic) {
                            // Static star import (e.g., import static org.assertj.core.api.Assertions.*)
                            for (String usedMember : usedStaticMembers) {
                                if (usedMember.startsWith(packagePrefix)) {
                                    isUsed = true;
                                    break;
                                }
                            }
                        } else {
                            for (String usedType : usedTypes) {
                                if (usedType.startsWith(packagePrefix)) {
                                    isUsed = true;
                                    break;
                                }
                            }
                        }
                    } else if (isStatic) {
                        // Static import (e.g., import static org.assertj.core.api.Assertions.assertThat)
                        isUsed = usedStaticMembers.contains(importName);
                    } else {
                        // Regular type import
                        isUsed = usedTypes.contains(importName);
                    }

                    if (!isUsed) {
                        toRemove.add(importName);
                    }
                }

                // Remove unused imports
                for (String importToRemove : toRemove) {
                    IImportDeclaration imp = cu.getImport(importToRemove);
                    if (imp != null && imp.exists()) {
                        imp.delete(true, new NullProgressMonitor());
                    }
                }
            }

            // Sort remaining imports (by reorganizing import container)
            cu.getWorkingCopy(null);
            IImportDeclaration[] currentImports = cu.getImports();
            List<String> sortedImports = new java.util.ArrayList<>();
            for (IImportDeclaration imp : currentImports) {
                sortedImports.add(imp.getElementName() + (imp.isOnDemand() ? "" : ""));
            }
            java.util.Collections.sort(sortedImports);

            // Get imports after
            cu.makeConsistent(new NullProgressMonitor());
            List<String> importsAfter = new java.util.ArrayList<>();
            for (IImportDeclaration imp : cu.getImports()) {
                importsAfter.add(imp.getElementName());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("filePath", filePath);
            result.put("importsBefore", importsBefore.size());
            result.put("importsAfter", importsAfter.size());
            result.put("removedImports", toRemove);
            result.put("message", "Organize imports completed - removed " + toRemove.size() + " unused imports");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return ToolErrors.errorResult("organize imports", e);
        }
    }
}
