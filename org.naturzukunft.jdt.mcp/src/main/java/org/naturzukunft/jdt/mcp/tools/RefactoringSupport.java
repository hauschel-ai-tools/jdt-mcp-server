package org.naturzukunft.jdt.mcp.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.naturzukunft.jdt.mcp.McpLogger;

/**
 * Shared utility methods for refactoring tools.
 *
 * Extracted from RefactoringTools to improve separation of concerns.
 * Contains element lookup, status analysis, and change description helpers
 * used across all refactoring operations.
 */
class RefactoringSupport {

    /**
     * Global lock for workspace-mutating operations (refactoring, code generation, creation).
     * Eclipse JDT requires exclusive workspace access — concurrent refactoring operations
     * violate internal assumptions and cause AssertionFailedException or connection crashes.
     * All mutating MCP tools must acquire this lock before modifying workspace state.
     */
    static final java.util.concurrent.locks.ReentrantLock WORKSPACE_MUTATION_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    private RefactoringSupport() {
        // utility class
    }

    /**
     * Finds IType in the project that OWNS the source file (non-binary).
     * This is critical for refactoring: the element must come from the
     * declaring project so that ICompilationUnit is editable and bindings
     * are resolved via source, not class files.
     */
    static IType findTypeInSourceProject(String fullyQualifiedName) throws Exception {
        IType fallback = null;
        for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects()) {
            if (!project.getProject().isOpen()) continue;
            IType candidate = project.findType(fullyQualifiedName);
            if (candidate == null) continue;
            // Ideal: source type whose resource lives in this project
            if (!candidate.isBinary() && candidate.getResource() != null) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * Finds IPackageFragment by name across all source roots of all projects.
     */
    static IPackageFragment findPackageInSourceProject(String packageName) throws Exception {
        for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects()) {
            if (!project.getProject().isOpen()) continue;
            for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    IPackageFragment pkg = root.getPackageFragment(packageName);
                    if (pkg != null && pkg.exists()) {
                        return pkg;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Helper: Find a Java element by name and type.
     */
    static IJavaElement findElement(String elementName, String elementType) {
        try {
            switch (elementType.toUpperCase()) {
                case "PACKAGE" -> {
                    return findPackageInSourceProject(elementName);
                }
                case "CLASS", "INTERFACE", "ENUM", "TYPE" -> {
                    return findTypeInSourceProject(elementName);
                }
                case "METHOD" -> {
                    // Format: com.example.Class#methodName or com.example.Class.methodName
                    int separator = elementName.lastIndexOf('#');
                    if (separator == -1) {
                        separator = elementName.lastIndexOf('.');
                    }
                    if (separator > 0) {
                        String className = elementName.substring(0, separator);
                        String methodNamePart = elementName.substring(separator + 1);
                        IType type = findTypeInSourceProject(className);
                        if (type != null) {
                            for (IMethod method : type.getMethods()) {
                                if (method.getElementName().equals(methodNamePart)) {
                                    return method;
                                }
                            }
                        }
                    }
                }
                case "FIELD" -> {
                    // Format: com.example.Class#fieldName or com.example.Class.fieldName
                    int separator = elementName.lastIndexOf('#');
                    if (separator == -1) {
                        separator = elementName.lastIndexOf('.');
                    }
                    if (separator > 0) {
                        String className = elementName.substring(0, separator);
                        String fieldNamePart = elementName.substring(separator + 1);
                        IType type = findTypeInSourceProject(className);
                        if (type != null) {
                            IField field = type.getField(fieldNamePart);
                            if (field != null && field.exists()) {
                                return field;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            McpLogger.error("RefactoringSupport", "Error finding element: " + e.getMessage());
        }
        return null;
    }

    /**
     * Checks refactoring status for real errors, filtering out harmless participant
     * errors that occur in headless mode (Launch/Breakpoint/Watchpoint participants).
     * Returns list of real error messages, or empty list if only participant errors.
     */
    static List<String> getRealErrors(RefactoringStatus status) {
        if (!status.hasError()) {
            return List.of();
        }
        List<String> realErrors = new java.util.ArrayList<>();
        for (var entry : status.getEntries()) {
            if (entry.getSeverity() >= RefactoringStatus.ERROR) {
                String msg = entry.getMessage();
                // Skip harmless headless-mode errors
                if (msg != null && msg.contains("participant")) continue;
                // "potential matches" are informational, not blocking errors
                if (msg != null && msg.toLowerCase().contains("potential match")) continue;
                realErrors.add(msg);
            }
        }
        return realErrors;
    }

    /**
     * Extract non-participant warnings from refactoring status.
     */
    static List<String> getNonParticipantWarnings(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .filter(msg -> msg == null || !msg.contains("participant"))
                .toList();
    }

    /**
     * Extract messages from RefactoringStatus.
     */
    static List<String> extractStatusMessages(RefactoringStatus status) {
        return java.util.Arrays.stream(status.getEntries())
                .map(entry -> entry.getMessage())
                .toList();
    }

    /**
     * Count the total number of leaf (non-composite) changes in a change tree.
     * For rename, each leaf typically represents one file modification.
     */
    static int countLeafChanges(Change change) {
        if (change == null) {
            return 0;
        }
        if (change instanceof CompositeChange compositeChange) {
            int count = 0;
            for (Change child : compositeChange.getChildren()) {
                count += countLeafChanges(child);
            }
            return count;
        }
        return 1;
    }

    /**
     * Performs a refactoring Change with proper document lifecycle management.
     * Acquires {@link #WORKSPACE_MUTATION_LOCK} to prevent concurrent workspace modifications.
     * Calls initializeValidationData() before perform() to ensure the internal
     * document buffer reference counting (fAcquiredDocumentCount) is correctly
     * initialized. Without this, CompositeChange trees (e.g. package rename
     * with renameSubpackages=true) fail with AssertionFailedException in
     * TextFileChange.releaseDocument().
     */
    static void performChange(Change change, IProgressMonitor monitor) throws CoreException {
        WORKSPACE_MUTATION_LOCK.lock();
        try {
            NullProgressMonitor npm = new NullProgressMonitor();

            // Force all TextFileChange instances to save after applying edits
            applyForceSave(change);

            change.initializeValidationData(npm);

            // Execute each child individually to isolate AssertionFailExceptions.
            ResourcesPlugin.getWorkspace().run((IProgressMonitor m) -> {
                if (change instanceof CompositeChange composite) {
                    for (Change child : composite.getChildren()) {
                        performSingleChange(child, npm);
                    }
                } else {
                    performSingleChange(change, npm);
                }
            }, npm);

            // Refresh all projects to ensure filesystem is in sync
            for (var project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                try {
                    if (project.isOpen()) {
                        project.refreshLocal(IResource.DEPTH_INFINITE, npm);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        } finally {
            WORKSPACE_MUTATION_LOCK.unlock();
        }
    }

    /**
     * Performs a single change, handling headless-mode issues:
     * - Catches AssertionFailedException from TextFileChange.releaseDocument()
     * - Falls back to manual file operations for MoveCompilationUnitChange
     *   (which requires org.eclipse.jdt.ui, unavailable in headless mode)
     */
    private static void performSingleChange(Change change, NullProgressMonitor npm) throws CoreException {
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                performSingleChange(child, npm);
            }
            return;
        }

        String className = change.getClass().getSimpleName();

        // MoveCompilationUnitChange internally calls CompilationUnit.move() which
        // triggers activation of org.eclipse.jdt.ui — unavailable in headless mode.
        // Fall back to manual IResource copy + delete.
        if (className.equals("MoveCompilationUnitChange")) {
            performManualMove(change, npm);
            return;
        }

        try {
            change.perform(npm);
        } catch (org.eclipse.core.runtime.AssertionFailedException e) {
            // In headless mode, TextFileChange.releaseDocument() throws Assert.
            // The edits may or may not have been applied to the buffer.
            // Bypass Eclipse's buffer system: extract the TextEdit, read file
            // from disk, apply edit, write back.
            if (change instanceof TextFileChange tfc) {
                applyTextFileChangeManually(tfc, npm);
            }
            McpLogger.warn("RefactoringSupport",
                    "AssertionFailedException in " + className
                    + " [" + change.getName() + "] (applied manually): " + e.getMessage());
        }
    }

    /**
     * Manual file move as fallback for MoveCompilationUnitChange.
     * MoveCompilationUnitChange internally calls CompilationUnit.move() which
     * triggers activation of org.eclipse.jdt.ui — unavailable in headless mode.
     *
     * Extracts source CU and destination package, reads content, creates target, deletes source.
     * The package declaration update has already been applied by a preceding CompilationUnitChange,
     * so the source content already has the correct package declaration.
     */
    private static void performManualMove(Change change, NullProgressMonitor npm) throws CoreException {
        try {
            Object modified = change.getModifiedElement();
            if (!(modified instanceof org.eclipse.jdt.core.ICompilationUnit sourceCu)) {
                McpLogger.warn("RefactoringSupport",
                        "MoveCompilationUnitChange: modifiedElement is not ICompilationUnit: "
                        + (modified == null ? "null" : modified.getClass().getName()));
                return;
            }

            // Extract destination package from the change using reflection.
            // MoveCompilationUnitChange extends CompilationUnitReorgChange which
            // stores the destination as an IPackageFragment.
            org.eclipse.jdt.core.IPackageFragment destPkg = extractDestination(change);

            if (destPkg == null) {
                // Fallback: parse the change name "Move compilation unit 'X.java' to 'pkg.name'"
                String name = change.getName();
                var matcher = java.util.regex.Pattern.compile("to '([^']+)'").matcher(name);
                if (matcher.find()) {
                    String pkgName = matcher.group(1);
                    destPkg = findPackageInSourceProject(pkgName);
                }
            }

            if (destPkg == null) {
                McpLogger.warn("RefactoringSupport",
                        "MoveCompilationUnitChange: could not determine destination package");
                return;
            }

            // Read source content from DISK, not from CU buffer.
            // After AssertionFailedException in the preceding CompilationUnitChange,
            // the CU's buffer state is corrupted — sourceCu.getSource() triggers
            // org.eclipse.jdt.ui activation (NoClassDefFoundError: DocumentAdapter).
            org.eclipse.core.resources.IResource sourceResource = sourceCu.getResource();
            if (!(sourceResource instanceof org.eclipse.core.resources.IFile sourceFile)) {
                McpLogger.warn("RefactoringSupport",
                        "MoveCompilationUnitChange: source resource is not IFile");
                return;
            }
            String source = java.nio.file.Files.readString(sourceFile.getLocation().toFile().toPath());
            String cuName = sourceCu.getElementName();

            // Ensure the package declaration matches the destination
            String destPkgName = destPkg.getElementName();
            String sourcePkgName = sourceCu.getParent().getElementName();
            if (!destPkgName.equals(sourcePkgName)) {
                String oldPkgDecl = "package " + sourcePkgName + ";";
                String newPkgDecl = destPkgName.isEmpty() ? "" : "package " + destPkgName + ";";
                source = source.replace(oldPkgDecl, newPkgDecl);
            }

            // Create destination package if it doesn't exist
            if (!destPkg.exists()) {
                org.eclipse.jdt.core.IPackageFragmentRoot root =
                        (org.eclipse.jdt.core.IPackageFragmentRoot) destPkg.getParent();
                destPkg = root.createPackageFragment(destPkgName, true, npm);
            }

            // Create target compilation unit
            destPkg.createCompilationUnit(cuName, source, true, npm);

            // Delete source file
            if (sourceFile.exists()) {
                sourceFile.delete(true, npm);
            }

            McpLogger.info("RefactoringSupport",
                    "Manual move: " + cuName + " → " + destPkgName);

        } catch (Exception e) {
            throw new CoreException(new org.eclipse.core.runtime.Status(
                    org.eclipse.core.runtime.IStatus.ERROR, "org.naturzukunft.jdt.mcp",
                    "Manual move failed: " + e.getMessage(), e));
        }
    }

    /**
     * Extracts the destination IPackageFragment from a MoveCompilationUnitChange
     * by searching the class hierarchy for a field of type IPackageFragment.
     */
    private static org.eclipse.jdt.core.IPackageFragment extractDestination(Change change) {
        Class<?> clazz = change.getClass();
        while (clazz != null && !clazz.equals(Object.class)) {
            for (var field : clazz.getDeclaredFields()) {
                if (org.eclipse.jdt.core.IPackageFragment.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(change);
                        if (value instanceof org.eclipse.jdt.core.IPackageFragment pkg) {
                            McpLogger.info("RefactoringSupport",
                                    "Found destination via field " + clazz.getSimpleName() + "." + field.getName());
                            return pkg;
                        }
                    } catch (Exception e) {
                        // continue searching
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Applies a TextFileChange's edits directly to the file on disk, bypassing
     * Eclipse's buffer mechanism entirely. Used as fallback when
     * TextFileChange.perform() throws AssertionFailedException.
     *
     * Extracts the TextEdit via reflection, reads the file from disk,
     * applies the edit, and writes the result back.
     */
    private static void applyTextFileChangeManually(TextFileChange tfc, NullProgressMonitor npm) {
        try {
            org.eclipse.core.resources.IFile file = tfc.getFile();
            if (file == null || !file.exists()) return;

            // Extract TextEdit via reflection (protected in TextChange)
            org.eclipse.text.edits.TextEdit edit = null;
            Class<?> clazz = tfc.getClass();
            while (clazz != null) {
                try {
                    var field = clazz.getDeclaredField("fEdit");
                    field.setAccessible(true);
                    edit = (org.eclipse.text.edits.TextEdit) field.get(tfc);
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (edit == null) {
                McpLogger.warn("RefactoringSupport",
                        "Could not extract TextEdit from " + tfc.getClass().getSimpleName());
                return;
            }

            // Read current file content from disk
            java.nio.file.Path filePath = file.getLocation().toFile().toPath();
            String content = java.nio.file.Files.readString(filePath);

            // Apply the edit to the content
            org.eclipse.jface.text.Document doc = new org.eclipse.jface.text.Document(content);
            edit.copy().apply(doc);

            // Write back to disk via IFile
            String charset = file.getCharset() != null ? file.getCharset() : "UTF-8";
            byte[] bytes = doc.get().getBytes(charset);
            file.setContents(new java.io.ByteArrayInputStream(bytes),
                    IResource.FORCE | IResource.KEEP_HISTORY, npm);

        } catch (Exception e) {
            McpLogger.warn("RefactoringSupport",
                    "Manual TextFileChange apply failed for " + tfc.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Recursively sets FORCE_SAVE on all TextFileChange instances in a change tree.
     */
    private static void applyForceSave(Change change) {
        if (change instanceof TextFileChange tfc) {
            tfc.setSaveMode(TextFileChange.FORCE_SAVE);
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                applyForceSave(child);
            }
        }
    }

    /**
     * Describe the changes that would be made.
     */
    static Map<String, Object> describeChange(Change change) {
        Map<String, Object> desc = new HashMap<>();
        if (change != null) {
            desc.put("name", change.getName());

            if (change instanceof CompositeChange compositeChange) {
                List<Map<String, Object>> children = new java.util.ArrayList<>();
                for (Change child : compositeChange.getChildren()) {
                    children.add(describeChange(child));
                }
                desc.put("children", children);
                desc.put("childCount", children.size());
            }

            // Try to get affected files
            Object modifiedElement = change.getModifiedElement();
            if (modifiedElement != null) {
                desc.put("modifiedElement", modifiedElement.toString());
            }
        }
        return desc;
    }
}
