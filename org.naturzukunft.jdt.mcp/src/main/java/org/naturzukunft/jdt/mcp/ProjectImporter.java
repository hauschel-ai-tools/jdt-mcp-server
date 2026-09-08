package org.naturzukunft.jdt.mcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Imports projects from a directory into the Eclipse workspace.
 * Supports existing Eclipse projects (.project), Maven projects (pom.xml),
 * Gradle projects (build.gradle / build.gradle.kts), and plain Java projects.
 */
public class ProjectImporter {

    /**
     * Tracks all directories that were explicitly imported via {@link #importFromPath(Path, IProgressMonitor)}
     * or {@link #importFromDirectory(Path, IProgressMonitor)}. Used by reload to re-import all roots.
     */
    private static final Set<Path> importedRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Returns all directories that were previously imported, for use by workspace reload.
     */
    public static Set<Path> getImportedRoots() {
        return Set.copyOf(importedRoots);
    }

    /**
     * Clears the tracked import roots (used before re-populating during reload).
     */
    public static void clearImportedRoots() {
        importedRoots.clear();
    }

    /**
     * Records where a module's classpath stood when we last resolved it (see
     * {@link MavenClasspathFreshness#fingerprint(Path)}). A persistent project property lives in
     * the workspace metadata, so it neither touches the source tree nor depends on JDT actually
     * rewriting {@code .classpath} -- JDT skips that write when the resolved classpath is
     * unchanged, which would make a timestamp taken from the file itself never advance.
     */
    private static final QualifiedName CLASSPATH_STAMP =
            new QualifiedName("org.naturzukunft.jdt.mcp", "mavenClasspathStamp");

    /**
     * Outcome of one import run.
     *
     * @param projects     the projects that were imported or reopened
     * @param reopened     how many of them were already in the workspace, and therefore carry a
     *                     build state and problem markers from an earlier session
     * @param reconfigured how many reopened projects needed their classpath resolved again
     */
    public record ImportResult(List<IProject> projects, int reopened, int reconfigured) {
    }

    /** Mutable per-run tally, handed down the import call chain (never shared between runs). */
    private static final class ImportStats {
        private int reopened;
        private int reconfigured;
    }

    /**
     * Imports projects from the given directory into the workspace.
     *
     * @param directory the root directory to import from
     * @param monitor progress monitor
     * @return list of imported projects
     */
    public static ImportResult importFromDirectory(Path directory, IProgressMonitor monitor) {
        ImportStats stats = new ImportStats();
        List<IProject> imported = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            McpLogger.error("ProjectImporter", "Not a directory: " + directory);
            return new ImportResult(imported, stats.reopened, stats.reconfigured);
        }

        // Track this directory as an import root for reload
        importedRoots.add(directory.toAbsolutePath().normalize());

        Path pomFile = directory.resolve("pom.xml");
        Path projectFile = directory.resolve(".project");

        if (Files.exists(pomFile)) {
            // Maven project (check BEFORE .project — Maven projects often have .project too)
            imported.addAll(importMavenProject(directory, monitor, stats));
        } else if (isGradleProject(directory)) {
            // Gradle project
            IProject project = importGradleProject(directory, monitor, stats);
            if (project != null) {
                imported.add(project);
            }
        } else if (Files.exists(projectFile)) {
            // Eclipse project without Maven/Gradle
            IProject project = importExistingProject(directory, monitor, stats);
            if (project != null) {
                imported.add(project);
            }
        } else {
            // No project markers in root — scan subdirectories for projects
            imported.addAll(scanSubdirectories(directory, monitor, stats));
        }

        // Wire up inter-project dependencies so JDT can resolve cross-module references
        if (imported.size() > 1) {
            setupInterProjectDependencies(imported, monitor);
        }

        return new ImportResult(imported, stats.reopened, stats.reconfigured);
    }

    /**
     * Imports an existing Eclipse project (has .project file).
     */
    private static IProject importExistingProject(Path projectDir, IProgressMonitor monitor, ImportStats stats) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            org.eclipse.core.runtime.IPath descriptionPath =
                    new org.eclipse.core.runtime.Path(projectDir.resolve(".project").toString());
            IProjectDescription description = workspace.loadProjectDescription(descriptionPath);

            // Set the location to the external directory
            description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));

            IProject project = workspace.getRoot().getProject(description.getName());
            if (project.exists()) {
                reopenExistingProject(project, monitor, stats);
                McpLogger.info("ProjectImporter", "Opened existing project: " + description.getName());
            } else {
                project.create(description, monitor);
                project.open(monitor);
                McpLogger.info("ProjectImporter", "Imported Eclipse project: " + description.getName());
            }
            return project;

        } catch (Exception e) {
            McpLogger.error("ProjectImporter", "Failed to import Eclipse project from " + projectDir, e);
            return null;
        }
    }

    /**
     * Imports a Maven project. Handles multi-module projects by reading pom.xml for modules.
     */
    private static List<IProject> importMavenProject(Path projectDir, IProgressMonitor monitor, ImportStats stats) {
        List<IProject> imported = new ArrayList<>();

        // Check for multi-module
        List<String> modules = readMavenModules(projectDir.resolve("pom.xml"));

        if (!modules.isEmpty()) {
            McpLogger.info("ProjectImporter", "Multi-module Maven project with " + modules.size() + " modules");

            // Import each module
            for (String module : modules) {
                Path moduleDir = projectDir.resolve(module);
                if (!Files.isDirectory(moduleDir)) {
                    McpLogger.warn("ProjectImporter", "Module directory not found: " + moduleDir);
                    continue;
                }

                Path modulePom = moduleDir.resolve("pom.xml");
                if (Files.exists(modulePom)) {
                    // Check if module itself is multi-module (recursive)
                    List<String> subModules = readMavenModules(modulePom);
                    if (!subModules.isEmpty()) {
                        imported.addAll(importMavenProject(moduleDir, monitor, stats));
                    } else {
                        IProject project = createMavenModuleProject(moduleDir, module, monitor, stats);
                        if (project != null) {
                            imported.add(project);
                        }
                    }
                } else if (Files.exists(moduleDir.resolve(".project"))) {
                    // Fallback: Eclipse project without pom.xml
                    IProject project = importExistingProject(moduleDir, monitor, stats);
                    if (project != null) {
                        imported.add(project);
                    }
                } else {
                    // Module dir exists but has neither pom.xml nor .project
                    McpLogger.warn("ProjectImporter",
                            "Module '" + module + "' has no pom.xml or .project, importing as basic project");
                    IProject project = importBasicJavaProject(moduleDir, monitor, stats);
                    if (project != null) {
                        imported.add(project);
                    }
                }
            }

            // Also import the parent if it has source directories
            if (hasSourceDirectories(projectDir)) {
                IProject parent = createMavenModuleProject(projectDir, projectDir.getFileName().toString(), monitor, stats);
                if (parent != null) {
                    imported.add(parent);
                }
            }
        } else {
            // Single-module Maven project
            IProject project = createMavenModuleProject(projectDir, projectDir.getFileName().toString(), monitor, stats);
            if (project != null) {
                imported.add(project);
            }
        }

        return imported;
    }

    /**
     * Creates a Java project for a Maven module with standard layout.
     */
    private static IProject createMavenModuleProject(Path moduleDir, String projectName, IProgressMonitor monitor,
            ImportStats stats) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProjectDescription description = workspace.newProjectDescription(projectName);
            description.setLocation(new org.eclipse.core.runtime.Path(moduleDir.toString()));
            description.setNatureIds(new String[] { JavaCore.NATURE_ID });

            // Explicitly add the Java builder — in headless mode, nature.configure()
            // during project.open() may not reliably add the builder to the build spec
            ICommand buildCommand = description.newCommand();
            buildCommand.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new ICommand[] { buildCommand });

            IProject project = workspace.getRoot().getProject(projectName);
            if (project.exists()) {
                reopenExistingProject(project, monitor, stats);

                // The .classpath left behind by the previous import is a snapshot of the POM at
                // that time. If the POM chain has changed since, keeping it means building against
                // dependencies that are gone and missing the ones that were added -- JDT reports
                // build path errors that `mvn` does not (#114).
                Optional<String> staleReason =
                        MavenClasspathFreshness.staleReason(moduleDir, readClasspathStamp(project));
                if (staleReason.isEmpty()) {
                    // Compliance is read from the POM chain only, no `mvn` process involved, so a
                    // reopened project gets it as well -- a workspace created before #82 would
                    // otherwise keep compiling at the workspace default forever.
                    applyCompilerCompliance(JavaCore.create(project), moduleDir.resolve("pom.xml"));
                    McpLogger.info("ProjectImporter",
                            "Opened existing project: " + projectName + " (classpath up to date)");
                    return project;
                }

                McpLogger.info("ProjectImporter", "Opened existing project: " + projectName
                        + " — re-resolving classpath (" + staleReason.get() + ")");
                configureMavenProject(project, moduleDir, projectName, monitor);
                stats.reconfigured++;
                return project;
            }

            project.create(description, monitor);
            project.open(monitor);

            configureMavenProject(project, moduleDir, projectName, monitor);
            return project;

        } catch (Exception e) {
            McpLogger.error("ProjectImporter", "Failed to create Maven project: " + projectName, e);
            return null;
        }
    }

    /**
     * Opens a project that is already in the workspace and refreshes it from disk.
     *
     * <p>The refresh is what makes a reopened project trustworthy: the workspace tree (and with it
     * every problem marker) was persisted when the previous session ended, and Eclipse does not
     * notice on its own that files changed while no session was running. Without the refresh the
     * server answers from the last session's state -- reporting problems that have long been fixed
     * and missing the ones that were introduced (#114).
     */
    private static void reopenExistingProject(IProject project, IProgressMonitor monitor, ImportStats stats)
            throws Exception {
        project.open(monitor);
        stats.reopened++;
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not refresh reopened project " + project.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Configures an open project as a Java project for a Maven module: source folders, JRE
     * container, resolved Maven dependencies, output location and compiler compliance. Used both
     * for a freshly created project and for a reopened one whose {@code .classpath} went stale.
     */
    private static void configureMavenProject(IProject project, Path moduleDir, String projectName,
            IProgressMonitor monitor) throws Exception {
        IJavaProject javaProject = JavaCore.create(project);

        List<IClasspathEntry> entries = new ArrayList<>();

        // Add source folders that exist. Test sources get their own output folder
        // (target/test-classes) so they don't compile into target/classes and end up
        // packaged into the module's jar (#84).
        addSourceFolderIfExists(project, entries, "src/main/java");
        addSourceFolderIfExists(project, entries, "src/test/java", "target/test-classes");
        addSourceFolderIfExists(project, entries, "src/main/resources");
        addSourceFolderIfExists(project, entries, "src/test/resources", "target/test-classes");

        // If no standard Maven dirs found, check for src/ directly
        if (entries.isEmpty()) {
            addSourceFolderIfExists(project, entries, "src");
        }

        // Add JRE container
        entries.add(JavaCore.newContainerEntry(
                new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        // Add Maven dependencies from local repository
        addMavenDependencies(moduleDir, entries);

        // Warn if Lombok is in dependencies but agent is not loaded
        checkLombokInClasspath(entries, projectName);

        javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), monitor);

        // Set output location
        org.eclipse.core.runtime.IPath outputPath = project.getFullPath().append("target/classes");
        javaProject.setOutputLocation(outputPath, monitor);

        // Set compiler compliance from the module's (or an ancestor's) pom.xml, so the
        // project compiles at its own Maven release instead of silently inheriting the
        // workspace default (#82).
        applyCompilerCompliance(javaProject, moduleDir.resolve("pom.xml"));

        writeClasspathStamp(project, moduleDir);

        McpLogger.info("ProjectImporter", "Configured Maven project: " + projectName +
                " with " + entries.size() + " classpath entries");
    }

    /**
     * Returns the classpath stamp stored for {@code project}, or {@code null} when there is none
     * (a workspace from a version before this stamp existed, or a project we never resolved).
     */
    private static String readClasspathStamp(IProject project) {
        try {
            return project.getPersistentProperty(CLASSPATH_STAMP);
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not read classpath stamp of " + project.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Records the state of the module's POM chain right after its classpath was resolved from it.
     * A failure here only costs one needless re-resolution on the next start, so it is logged and
     * swallowed.
     */
    private static void writeClasspathStamp(IProject project, Path moduleDir) {
        try {
            project.setPersistentProperty(CLASSPATH_STAMP, MavenClasspathFreshness.fingerprint(moduleDir));
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not store classpath stamp for " + project.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Sets {@link IJavaProject} compiler compliance/source/target from the Maven compiler
     * release declared for {@code pomFile} (see {@link MavenCompilerCompliance}). Leaves the
     * project's options untouched (workspace default) when the POM chain declares none.
     */
    private static void applyCompilerCompliance(IJavaProject javaProject, Path pomFile) {
        MavenCompilerCompliance.resolve(pomFile).ifPresent(compliance -> {
            String level = compliance.version();
            if (!JavaCore.isSupportedJavaVersion(level)) {
                String fallback = JavaCore.latestSupportedJavaVersion();
                McpLogger.warn("ProjectImporter", "Compiler release " + level + " from " + compliance.propertyKey()
                        + " (" + compliance.pomFile() + ") is not supported by this JDT version, using highest "
                        + "supported level " + fallback + " instead");
                level = fallback;
            }
            javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, level);
            javaProject.setOption(JavaCore.COMPILER_SOURCE, level);
            javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, level);
            McpLogger.info("ProjectImporter", "compliance " + level + " from " + compliance.pomFile());
        });
    }

    /**
     * Imports a directory as a basic Java project (no pom.xml, no .project).
     */
    private static IProject importBasicJavaProject(Path projectDir, IProgressMonitor monitor, ImportStats stats) {
        try {
            String projectName = projectDir.getFileName().toString();
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProjectDescription description = workspace.newProjectDescription(projectName);
            description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
            description.setNatureIds(new String[] { JavaCore.NATURE_ID });

            // Explicitly add the Java builder for headless mode reliability
            ICommand buildCommand = description.newCommand();
            buildCommand.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new ICommand[] { buildCommand });

            IProject project = workspace.getRoot().getProject(projectName);
            if (project.exists()) {
                reopenExistingProject(project, monitor, stats);
                return project;
            }

            project.create(description, monitor);
            project.open(monitor);

            IJavaProject javaProject = JavaCore.create(project);
            List<IClasspathEntry> entries = new ArrayList<>();

            // Try common source layouts
            addSourceFolderIfExists(project, entries, "src/main/java");
            addSourceFolderIfExists(project, entries, "src/test/java");
            if (entries.isEmpty()) {
                addSourceFolderIfExists(project, entries, "src");
            }

            entries.add(JavaCore.newContainerEntry(
                    new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

            javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), monitor);

            McpLogger.info("ProjectImporter", "Created basic Java project: " + projectName);
            return project;

        } catch (Exception e) {
            McpLogger.error("ProjectImporter", "Failed to create basic Java project from " + projectDir, e);
            return null;
        }
    }

    /**
     * Adds a source folder to classpath entries if the folder exists on disk.
     * Uses the project's default output location (e.g. main sources compiling into
     * {@code target/classes}).
     */
    private static void addSourceFolderIfExists(IProject project, List<IClasspathEntry> entries, String folderPath) {
        addSourceFolderIfExists(project, entries, folderPath, null);
    }

    /**
     * Adds a source folder to classpath entries if the folder exists on disk, with an
     * optional dedicated output location. Test sources need their own output folder
     * (e.g. Maven's {@code target/test-classes}, Gradle's {@code build/classes/java/test})
     * so they neither compile into the main output nor end up packaged into the main jar.
     * Pass {@code null} to fall back to the project's default output location.
     */
    private static void addSourceFolderIfExists(IProject project, List<IClasspathEntry> entries, String folderPath,
            String outputFolderPath) {
        Path absolutePath = Path.of(project.getLocation().toOSString(), folderPath);
        if (!Files.isDirectory(absolutePath)) {
            return;
        }
        org.eclipse.core.runtime.IPath srcPath = project.getFullPath().append(folderPath);
        if (outputFolderPath == null) {
            entries.add(JavaCore.newSourceEntry(srcPath));
            return;
        }
        org.eclipse.core.runtime.IPath outputPath = project.getFullPath().append(outputFolderPath);
        entries.add(JavaCore.newSourceEntry(srcPath, new org.eclipse.core.runtime.IPath[0], outputPath));
    }

    /**
     * Resolves Maven dependencies and returns them as classpath entries.
     */
    public static List<IClasspathEntry> resolveMavenDependencies(Path moduleDir) {
        List<IClasspathEntry> entries = new ArrayList<>();
        addMavenDependencies(moduleDir, entries);
        return entries;
    }

    /**
     * Resolves Maven dependencies using 'mvn dependency:build-classpath' and adds them.
     */
    private static void addMavenDependencies(Path moduleDir, List<IClasspathEntry> entries) {
        try {
            Path cpFile = Files.createTempFile("jdtmcp-classpath-", ".txt");
            cpFile.toFile().deleteOnExit();

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-q", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile.toString(),
                    "-DincludeScope=test")
                    .directory(moduleDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                McpLogger.warn("ProjectImporter",
                        "mvn dependency:build-classpath timed out after 120s for " + moduleDir.getFileName());
                Files.deleteIfExists(cpFile);
                return;
            }
            int exitCode = process.exitValue();

            if (exitCode == 0 && Files.exists(cpFile) && Files.size(cpFile) > 0) {
                String classpath = Files.readString(cpFile).trim();
                if (!classpath.isEmpty()) {
                    String[] jars = classpath.split(File.pathSeparator);
                    int addedCount = 0;
                    for (String jar : jars) {
                        jar = jar.trim();
                        if (!jar.isEmpty() && Files.exists(Path.of(jar))) {
                            entries.add(JavaCore.newLibraryEntry(
                                    new org.eclipse.core.runtime.Path(jar), null, null));
                            addedCount++;
                        }
                    }
                    McpLogger.info("ProjectImporter",
                            "Added " + addedCount + " Maven dependencies (includeScope=test) for " + moduleDir.getFileName());
                }
            } else {
                McpLogger.warn("ProjectImporter",
                        "mvn dependency:build-classpath failed (exit=" + exitCode + ") for " + moduleDir.getFileName());
            }

            Files.deleteIfExists(cpFile);

        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not resolve Maven dependencies for " + moduleDir.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Sets up inter-project dependencies so JDT can resolve cross-module references.
     * For each project, parses its pom.xml to find dependencies on other workspace projects,
     * then adds project entries to the classpath (replacing any matching JAR entries).
     */
    public static void setupInterProjectDependencies(List<IProject> projects, IProgressMonitor monitor) {
        WorkspaceMavenModules modules = mapMavenModules(projects);
        Map<String, IProject> artifactToProject = modules.projectsByArtifactId();

        if (modules.size() < 2) {
            return; // Nothing to wire up
        }

        // Two strategies to discover inter-project dependencies:
        // 1. JAR matching: replace CPE_LIBRARY entries whose filename matches a workspace project
        // 2. POM parsing: parse <dependencies> from pom.xml and match artifactIds to workspace projects
        //    (needed when JARs aren't installed in ~/.m2/repository yet)
        int totalAdded = 0;
        for (IProject project : projects) {
            try {
                IJavaProject javaProject = JavaCore.create(project);
                if (javaProject == null || !javaProject.exists()) {
                    continue;
                }

                IClasspathEntry[] existing = javaProject.getRawClasspath();
                List<IClasspathEntry> newClasspath = new ArrayList<>();
                List<IProject> referencedProjects = new ArrayList<>();
                int addedCount = 0;
                int droppedCount = 0;

                // Every project already referenced by the raw classpath, collected up front:
                // a project entry may sit *after* the sibling JAR of the same module, and
                // turning that JAR into a second project entry makes setRawClasspath reject
                // the whole classpath with "Build path contains duplicate entry" -- which
                // used to abort the dependency setup for the module entirely (#116).
                Set<String> knownProjectRefs = new HashSet<>();
                for (IClasspathEntry entry : existing) {
                    if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                        knownProjectRefs.add(entry.getPath().lastSegment());
                    }
                }

                // Project names already written to newClasspath, so no name is emitted twice
                Set<String> placedProjectRefs = new HashSet<>();

                // Strategy 1: Replace matching JAR entries with project entries
                for (IClasspathEntry entry : existing) {
                    if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                        IProject matchedProject = findMatchingWorkspaceProject(
                                entry.getPath().toOSString(), modules, project);
                        if (matchedProject != null) {
                            if (knownProjectRefs.contains(matchedProject.getName())) {
                                // The workspace project is already on the classpath; this JAR is
                                // the stale ~/.m2 copy of the same module. Drop it, the project
                                // reference wins.
                                droppedCount++;
                                continue;
                            }
                            newClasspath.add(JavaCore.newProjectEntry(matchedProject.getFullPath()));
                            referencedProjects.add(matchedProject);
                            knownProjectRefs.add(matchedProject.getName());
                            placedProjectRefs.add(matchedProject.getName());
                            addedCount++;
                            continue;
                        }
                    }
                    if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                        String projName = entry.getPath().lastSegment();
                        if (!placedProjectRefs.add(projName)) {
                            // Duplicate project entry already present in the raw classpath
                            droppedCount++;
                            continue;
                        }
                        IProject refProject = ResourcesPlugin.getWorkspace().getRoot().getProject(projName);
                        if (refProject.exists()) {
                            referencedProjects.add(refProject);
                        }
                    }
                    newClasspath.add(entry);
                }

                // Strategy 2: Parse pom.xml <dependencies> for workspace project matches
                // This catches dependencies whose JARs aren't in the local Maven repo
                Path projectDir = Path.of(project.getLocation().toOSString());
                Path pomFile = projectDir.resolve("pom.xml");
                if (Files.exists(pomFile)) {
                    List<String> depArtifactIds = readMavenDependencyArtifactIds(pomFile);
                    for (String depArtifactId : depArtifactIds) {
                        IProject depProject = artifactToProject.get(depArtifactId);
                        if (depProject != null && !depProject.equals(project)
                                && placedProjectRefs.add(depProject.getName())) {
                            newClasspath.add(JavaCore.newProjectEntry(depProject.getFullPath()));
                            referencedProjects.add(depProject);
                            addedCount++;
                        }
                    }
                }

                if (addedCount > 0 || droppedCount > 0) {
                    javaProject.setRawClasspath(newClasspath.toArray(new IClasspathEntry[0]), monitor);

                    IProjectDescription desc = project.getDescription();
                    desc.setReferencedProjects(referencedProjects.toArray(new IProject[0]));
                    project.setDescription(desc, monitor);

                    totalAdded += addedCount;
                    McpLogger.info("ProjectImporter", "Added " + addedCount +
                            " project dependencies to " + project.getName()
                            + (droppedCount > 0 ? " (dropped " + droppedCount
                                    + " duplicate entries superseded by project references)" : ""));
                }
            } catch (Exception e) {
                McpLogger.warn("ProjectImporter",
                        "Could not set up project dependencies for " + project.getName() + ": " + e.getMessage());
            }
        }

        if (totalAdded > 0) {
            McpLogger.info("ProjectImporter",
                    "Set up " + totalAdded + " inter-project dependencies across " + projects.size() + " projects");
        }
    }

    /**
     * The Maven modules open in the workspace, indexed by artifactId: the project itself and
     * its groupId (which is {@code null} when the POM chain did not yield one).
     */
    public record WorkspaceMavenModules(Map<String, IProject> projectsByArtifactId,
            Map<String, String> groupIdsByArtifactId) {

        public int size() {
            return projectsByArtifactId.size();
        }
    }

    /**
     * Indexes the Maven modules among the given projects by artifactId. Projects without a
     * readable {@code pom.xml} are skipped.
     */
    public static WorkspaceMavenModules mapMavenModules(Collection<IProject> projects) {
        Map<String, IProject> projectsByArtifactId = new HashMap<>();
        Map<String, String> groupIdsByArtifactId = new HashMap<>();
        for (IProject project : projects) {
            if (project.getLocation() == null) {
                continue;
            }
            Path pomFile = Path.of(project.getLocation().toOSString()).resolve("pom.xml");
            if (Files.exists(pomFile)) {
                String artifactId = readMavenArtifactId(pomFile);
                if (artifactId != null) {
                    projectsByArtifactId.put(artifactId, project);
                    groupIdsByArtifactId.put(artifactId, readMavenGroupId(pomFile));
                }
            }
        }
        return new WorkspaceMavenModules(projectsByArtifactId, groupIdsByArtifactId);
    }

    /**
     * Returns the workspace project a JAR resolved from the local Maven repository is the
     * build output of, or {@code null} for a JAR that belongs to no workspace project. Takes
     * the full path, not just the file name: inside the repository layout the path carries
     * groupId and artifactId, which keeps a foreign artifact with a colliding artifactId
     * ({@code core}, {@code common}, {@code utils}) from being mistaken for a workspace
     * module. The project {@code self} never matches its own JAR.
     *
     * @see WorkspaceArtifactMatcher
     */
    public static IProject findMatchingWorkspaceProject(String jarPath,
            WorkspaceMavenModules modules, IProject self) {
        String artifactId = WorkspaceArtifactMatcher.matchArtifactId(jarPath,
                modules.groupIdsByArtifactId());
        if (artifactId == null) {
            return null;
        }
        IProject matched = modules.projectsByArtifactId().get(artifactId);
        return matched == null || matched.equals(self) ? null : matched;
    }

    /**
     * Reads the artifactId from a Maven pom.xml.
     */
    private static String readMavenArtifactId(Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            // Get direct child <artifactId> (not from <parent> or <dependency>)
            NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element el
                        && "artifactId".equals(el.getTagName())) {
                    return el.getTextContent().trim();
                }
            }
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter", "Could not read artifactId from " + pomFile + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Reads the groupId of a Maven pom.xml: the direct child &lt;groupId&gt; if present,
     * otherwise the one inherited from &lt;parent&gt; (the common case in a reactor, where
     * modules declare only their artifactId). Returns {@code null} when neither is there.
     */
    private static String readMavenGroupId(Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            Element parent = null;
            NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element el) {
                    if ("groupId".equals(el.getTagName())) {
                        return el.getTextContent().trim();
                    }
                    if ("parent".equals(el.getTagName())) {
                        parent = el;
                    }
                }
            }

            if (parent != null) {
                NodeList parentChildren = parent.getChildNodes();
                for (int i = 0; i < parentChildren.getLength(); i++) {
                    if (parentChildren.item(i) instanceof Element el
                            && "groupId".equals(el.getTagName())) {
                        return el.getTextContent().trim();
                    }
                }
            }
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter", "Could not read groupId from " + pomFile + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Reads artifactIds from the &lt;dependencies&gt; section of a Maven pom.xml.
     * Only reads direct child dependencies (not from dependencyManagement or profiles).
     */
    private static List<String> readMavenDependencyArtifactIds(Path pomFile) {
        List<String> artifactIds = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            // Find <dependencies> direct child of project root
            NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element depsEl
                        && "dependencies".equals(depsEl.getTagName())) {
                    NodeList deps = depsEl.getChildNodes();
                    for (int j = 0; j < deps.getLength(); j++) {
                        if (deps.item(j) instanceof Element depEl
                                && "dependency".equals(depEl.getTagName())) {
                            // Skip test-scoped dependencies
                            String scope = getChildText(depEl, "scope");
                            if ("test".equals(scope)) {
                                continue;
                            }
                            String artifactId = getChildText(depEl, "artifactId");
                            if (artifactId != null) {
                                artifactIds.add(artifactId);
                            }
                        }
                    }
                    break; // Only process first <dependencies> block
                }
            }
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not read dependencies from " + pomFile + ": " + e.getMessage());
        }
        return artifactIds;
    }

    private static String getChildText(Element parent, String childTagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el
                    && childTagName.equals(el.getTagName())) {
                return el.getTextContent().trim();
            }
        }
        return null;
    }

    /**
     * Reads module names from a Maven pom.xml.
     */
    private static List<String> readMavenModules(Path pomFile) {
        List<String> modules = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            NodeList modulesNodes = doc.getElementsByTagName("modules");
            if (modulesNodes.getLength() > 0) {
                Element modulesElement = (Element) modulesNodes.item(0);
                NodeList moduleNodes = modulesElement.getElementsByTagName("module");
                for (int i = 0; i < moduleNodes.getLength(); i++) {
                    String moduleName = moduleNodes.item(i).getTextContent().trim();
                    if (!moduleName.isEmpty()) {
                        modules.add(moduleName);
                    }
                }
            }
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter", "Could not parse pom.xml: " + pomFile + " - " + e.getMessage());
        }
        return modules;
    }

    /**
     * Scans immediate subdirectories for projects (.project, pom.xml, or build.gradle).
     * Falls back to importing the root as a basic Java project if no subprojects found.
     */
    private static List<IProject> scanSubdirectories(Path directory, IProgressMonitor monitor, ImportStats stats) {
        List<IProject> imported = new ArrayList<>();

        try (var entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path subDir : entries) {
                String name = subDir.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }

                if (Files.exists(subDir.resolve("pom.xml"))) {
                    imported.addAll(importMavenProject(subDir, monitor, stats));
                } else if (isGradleProject(subDir)) {
                    IProject project = importGradleProject(subDir, monitor, stats);
                    if (project != null) {
                        imported.add(project);
                    }
                } else if (Files.exists(subDir.resolve(".project"))) {
                    IProject project = importExistingProject(subDir, monitor, stats);
                    if (project != null) {
                        imported.add(project);
                    }
                }
            }
        } catch (Exception e) {
            McpLogger.warn("ProjectImporter", "Error scanning subdirectories: " + e.getMessage());
        }

        if (imported.isEmpty()) {
            // No subprojects found — try importing root as basic Java project
            IProject project = importBasicJavaProject(directory, monitor, stats);
            if (project != null) {
                imported.add(project);
            }
        }

        return imported;
    }

    /**
     * Imports a project from a specific path. Can be called at any time to add projects.
     * After importing, sets up inter-project dependencies with all existing workspace projects.
     */
    public static List<IProject> importFromPath(Path path, IProgressMonitor monitor) {
        List<IProject> imported = importFromDirectory(path, monitor).projects();

        // When adding projects later, wire up dependencies with ALL workspace projects
        if (!imported.isEmpty()) {
            try {
                IJavaProject[] allJavaProjects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                        .getJavaProjects();
                List<IProject> allProjects = new ArrayList<>();
                for (IJavaProject jp : allJavaProjects) {
                    allProjects.add(jp.getProject());
                }
                if (allProjects.size() > 1) {
                    setupInterProjectDependencies(allProjects, monitor);
                }
            } catch (Exception e) {
                McpLogger.warn("ProjectImporter",
                        "Could not set up cross-repo dependencies: " + e.getMessage());
            }
        }

        return imported;
    }

    /**
     * Checks if a directory has standard Maven source directories.
     */
    private static boolean hasSourceDirectories(Path dir) {
        return Files.isDirectory(dir.resolve("src/main/java")) ||
                Files.isDirectory(dir.resolve("src/test/java")) ||
                Files.isDirectory(dir.resolve("src"));
    }

    /**
     * Checks if a directory is a Gradle project.
     */
    private static boolean isGradleProject(Path dir) {
        return Files.exists(dir.resolve("build.gradle")) ||
                Files.exists(dir.resolve("build.gradle.kts"));
    }

    /**
     * Imports a Gradle project. Sets up source directories based on standard Gradle/Maven layout.
     * Resolves dependencies using 'gradle dependencies' if available.
     */
    private static IProject importGradleProject(Path projectDir, IProgressMonitor monitor, ImportStats stats) {
        try {
            String projectName = projectDir.getFileName().toString();
            McpLogger.info("ProjectImporter", "Importing Gradle project: " + projectName);

            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProjectDescription description = workspace.newProjectDescription(projectName);
            description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
            description.setNatureIds(new String[] { JavaCore.NATURE_ID });

            // Explicitly add the Java builder for headless mode reliability
            ICommand buildCommand = description.newCommand();
            buildCommand.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new ICommand[] { buildCommand });

            IProject project = workspace.getRoot().getProject(projectName);
            if (project.exists()) {
                reopenExistingProject(project, monitor, stats);
                McpLogger.info("ProjectImporter", "Opened existing Gradle project: " + projectName);
                return project;
            }

            project.create(description, monitor);
            project.open(monitor);

            IJavaProject javaProject = JavaCore.create(project);
            List<IClasspathEntry> entries = new ArrayList<>();

            // Standard Gradle/Maven source layout. Test sources get their own output folder
            // (build/classes/java/test) so they don't compile into build/classes/java/main (#84).
            addSourceFolderIfExists(project, entries, "src/main/java");
            addSourceFolderIfExists(project, entries, "src/test/java", "build/classes/java/test");
            addSourceFolderIfExists(project, entries, "src/main/resources");
            addSourceFolderIfExists(project, entries, "src/test/resources", "build/classes/java/test");
            // Kotlin source dirs
            addSourceFolderIfExists(project, entries, "src/main/kotlin");
            addSourceFolderIfExists(project, entries, "src/test/kotlin", "build/classes/java/test");

            if (entries.isEmpty()) {
                addSourceFolderIfExists(project, entries, "src");
            }

            // Add JRE container
            entries.add(JavaCore.newContainerEntry(
                    new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

            // Try to resolve Gradle dependencies
            addGradleDependencies(projectDir, entries);

            javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), monitor);

            org.eclipse.core.runtime.IPath outputPath = project.getFullPath().append("build/classes/java/main");
            javaProject.setOutputLocation(outputPath, monitor);

            McpLogger.info("ProjectImporter", "Created Gradle project: " + projectName +
                    " with " + entries.size() + " classpath entries");
            return project;

        } catch (Exception e) {
            McpLogger.error("ProjectImporter", "Failed to create Gradle project from " + projectDir, e);
            return null;
        }
    }

    /**
     * Resolves Gradle dependencies and adds them as classpath entries.
     */
    private static void addGradleDependencies(Path projectDir, List<IClasspathEntry> entries) {
        try {
            // Use gradle to get the runtime classpath
            String gradleCmd = Files.exists(projectDir.resolve("gradlew")) ? "./gradlew" : "gradle";

            Path cpFile = Files.createTempFile("jdtmcp-gradle-cp-", ".txt");
            cpFile.toFile().deleteOnExit();

            // Use a simple task to print the classpath
            ProcessBuilder pb = new ProcessBuilder(
                    gradleCmd, "-q", "dependencies", "--configuration", "compileClasspath",
                    "-PcpOutputFile=" + cpFile.toString())
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                McpLogger.warn("ProjectImporter",
                        "Gradle dependency resolution timed out for " + projectDir.getFileName());
                Files.deleteIfExists(cpFile);
                return;
            }

            // Fallback: scan build/libs and local cache for jars
            Path buildLibs = projectDir.resolve("build/libs");
            if (Files.isDirectory(buildLibs)) {
                try (var jars = Files.list(buildLibs)) {
                    jars.filter(p -> p.toString().endsWith(".jar"))
                            .forEach(jar -> entries.add(JavaCore.newLibraryEntry(
                                    new org.eclipse.core.runtime.Path(jar.toString()), null, null)));
                }
            }

            Files.deleteIfExists(cpFile);

        } catch (Exception e) {
            McpLogger.warn("ProjectImporter",
                    "Could not resolve Gradle dependencies for " + projectDir.getFileName() + ": " + e.getMessage());
        }
    }

    private static volatile boolean lombokWarningShown = false;

    /**
     * Checks if any classpath entry is a Lombok JAR and warns if the Lombok agent is not loaded.
     * Without the agent, JDT cannot see Lombok-generated members (getters, constructors, loggers, etc.),
     * causing phantom compile errors and refactoring failures.
     */
    private static void checkLombokInClasspath(List<IClasspathEntry> entries, String projectName) {
        if (lombokWarningShown) {
            return;
        }
        for (IClasspathEntry entry : entries) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                String jarName = entry.getPath().lastSegment();
                if (jarName != null && jarName.startsWith("lombok-") && jarName.endsWith(".jar")) {
                    if (!HeadlessApplication.isLombokAgentLoaded()) {
                        lombokWarningShown = true;
                        McpLogger.warn("ProjectImporter",
                                "Lombok dependency detected in '" + projectName + "' but Lombok agent is NOT loaded. " +
                                "This will cause phantom compile errors (unresolved getters, constructors, loggers). " +
                                "To fix: restart jdt-mcp (auto-detection should add -javaagent), " +
                                "or add '-javaagent:<path>/lombok.jar' to ~/.jdt-mcp/jdt-mcp.ini");
                    }
                    return;
                }
            }
        }
    }
}
