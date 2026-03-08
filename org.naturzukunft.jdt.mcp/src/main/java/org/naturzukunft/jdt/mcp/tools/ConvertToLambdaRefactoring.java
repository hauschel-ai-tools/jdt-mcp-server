package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
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
 * MCP tool for converting anonymous inner classes to lambda expressions.
 *
 * Extracted from RefactoringTools as part of #30 (God Class refactoring).
 */
class ConvertToLambdaRefactoring {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConvertToLambdaRefactoring() {
        // utility class
    }

    static ToolRegistration convertToLambdaTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Absolute file path to the Java file"),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Position inside the anonymous class (from jdt_parse_java_file)"),
                        "preview", Map.of(
                                "type", "boolean",
                                "description", "Preview changes without applying (default: false)")),
                List.of("filePath", "offset"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_convert_to_lambda",
                "Convert an anonymous inner class to a lambda expression. " +
                "Works for single-method interfaces (functional interfaces). " +
                "IMPORTANT: The offset must be INSIDE the anonymous class body (e.g., inside the method implementation), " +
                "NOT at the 'new' keyword. Use jdt_parse_java_file to find the correct offset within the anonymous class.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> convertToLambda(
                (String) args.get("filePath"),
                ((Number) args.get("offset")).intValue(),
                args.get("preview") != null ? (Boolean) args.get("preview") : false));
    }

    @SuppressWarnings("unchecked")
    private static CallToolResult convertToLambda(String filePath, int offset, boolean previewOnly) {
        try {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(filePath));
            if (file == null || !file.exists()) {
                return new CallToolResult("File not found: " + filePath, true);
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit cu)) {
                return new CallToolResult("Not a Java source file: " + filePath, true);
            }

            // Parse AST
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

            // Find anonymous class at offset
            org.eclipse.jdt.core.dom.ASTNode node = org.eclipse.jdt.core.dom.NodeFinder.perform(ast, offset, 0);

            // Navigate up to find AnonymousClassDeclaration
            org.eclipse.jdt.core.dom.AnonymousClassDeclaration anonClass = null;
            while (node != null) {
                if (node instanceof org.eclipse.jdt.core.dom.AnonymousClassDeclaration acd) {
                    anonClass = acd;
                    break;
                }
                node = node.getParent();
            }

            if (anonClass == null) {
                return new CallToolResult("No anonymous class found at position " + offset, true);
            }

            // Check if it's a functional interface (single abstract method)
            List<org.eclipse.jdt.core.dom.BodyDeclaration> bodyDecls = anonClass.bodyDeclarations();
            int methodCount = 0;
            org.eclipse.jdt.core.dom.MethodDeclaration singleMethod = null;

            for (org.eclipse.jdt.core.dom.BodyDeclaration decl : bodyDecls) {
                if (decl instanceof org.eclipse.jdt.core.dom.MethodDeclaration md) {
                    methodCount++;
                    singleMethod = md;
                }
            }

            if (methodCount != 1) {
                return new CallToolResult("Cannot convert - anonymous class must have exactly one method (found " + methodCount + ")", true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("filePath", filePath);
            result.put("offset", offset);
            result.put("methodName", singleMethod.getName().getIdentifier());

            // Get the ClassInstanceCreation parent
            org.eclipse.jdt.core.dom.ASTNode parent = anonClass.getParent();
            if (!(parent instanceof org.eclipse.jdt.core.dom.ClassInstanceCreation)) {
                return new CallToolResult("Unexpected AST structure", true);
            }

            // Build lambda expression manually
            StringBuilder lambda = new StringBuilder();
            List<org.eclipse.jdt.core.dom.SingleVariableDeclaration> params = singleMethod.parameters();

            if (params.isEmpty()) {
                lambda.append("()");
            } else if (params.size() == 1) {
                lambda.append(params.get(0).getName().getIdentifier());
            } else {
                lambda.append("(");
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) lambda.append(", ");
                    lambda.append(params.get(i).getName().getIdentifier());
                }
                lambda.append(")");
            }

            lambda.append(" -> ");

            // Get body
            org.eclipse.jdt.core.dom.Block body = singleMethod.getBody();
            if (body != null) {
                List<org.eclipse.jdt.core.dom.Statement> statements = body.statements();
                if (statements.size() == 1) {
                    org.eclipse.jdt.core.dom.Statement stmt = statements.get(0);
                    if (stmt instanceof org.eclipse.jdt.core.dom.ReturnStatement ret && ret.getExpression() != null) {
                        lambda.append(ret.getExpression().toString());
                    } else if (stmt instanceof org.eclipse.jdt.core.dom.ExpressionStatement expr) {
                        lambda.append(expr.getExpression().toString());
                    } else {
                        lambda.append(body.toString());
                    }
                } else {
                    lambda.append(body.toString());
                }
            }

            result.put("lambdaExpression", lambda.toString());

            if (previewOnly) {
                result.put("status", "PREVIEW");
                result.put("message", "Suggested lambda expression (manual replacement needed)");
                return new CallToolResult(MAPPER.writeValueAsString(result), false);
            }

            // For actual conversion, replace the source
            String source = cu.getSource();
            int start = parent.getStartPosition();
            int end = start + parent.getLength();

            String newSource = source.substring(0, start) + lambda.toString() + source.substring(end);

            ICompilationUnit workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
            try {
                workingCopy.getBuffer().setContents(newSource);
                workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
            } finally {
                workingCopy.discardWorkingCopy();
            }

            result.put("status", "SUCCESS");
            result.put("message", "Converted to lambda expression");

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error converting to lambda: " + e.getMessage(), true);
        }
    }
}
