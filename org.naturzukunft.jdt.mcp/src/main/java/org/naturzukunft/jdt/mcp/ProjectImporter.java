package org.naturzukunft.jdt.mcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
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
     * Imports projects from the given directory into the workspace.
     *
     * @param directory the root directory to import from
     * @param monitor progress monitor
     * @return list of imported projects
     */
    public static List<IProject> importFromDirectory(Path directory, IProgressMonitor monitor) {
        List<IProject> imported = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            McpLogger.error("ProjectImporter", "Not a directory: " + directory);
            return imported;
        }

        // Track this directory as an import root for reload
        importedRoots.add(directory.toAbsolutePath().normalize());

        Path pomFile = directory.resolve("pom.xml");
        Path projectFile = directory.resolve(".project");

        if (Files.exists(pomFile)) {
            // Maven project (check BEFORE .project — Maven projects often have .project too)
            imported.addAll(importMavenProject(directory, monitor));
        } else if (isGradleProject(directory)) {
            // Gradle project
            IProject project = importGradleProject(directory, monitor);
            if (project != null) {
                imported.add(project);
            }
        } else if (Files.exists(projectFile)) {
            // Eclipse project without Maven/Gradle
            IProject project = importExistingProject(directory, monitor);
            if (project != null) {
                imported.add(project);
            }
        } else {
            // No project markers in root — scan subdirectories for projects
            imported.addAll(scanSubdirectories(directory, monitor));
        }

        // Wire up inter-project dependencies so JDT can resolve cross-module references
        if (imported.size() > 1) {
            setupInterProjectDependencies(imported, monitor);
        }

        return imported;
    }

    /**
     * Imports an existing Eclipse project (has .project file).
     */
    private static IProject importExistingProject(Path projectDir, IProgressMonitor monitor) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            org.eclipse.core.runtime.IPath descriptionPath =
                    new org.eclipse.core.runtime.Path(projectDir.resolve(".project").toString());
            IProjectDescription description = workspace.loadProjectDescription(descriptionPath);

            // Set the location to the external directory
            description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));

            IProject project = workspace.getRoot().getProject(description.getName());
            if (project.exists()) {
                project.open(monitor);
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
    private static List<IProject> importMavenProject(Path projectDir, IProgressMonitor monitor) {
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
                        imported.addAll(importMavenProject(moduleDir, monitor));
                    } else {
                        IProject project = createMavenModuleProject(moduleDir, module, monitor);
                        if (project != null) {
                            imported.add(project);
                        }
                    }
                } else if (Files.exists(moduleDir.resolve(".project"))) {
                    // Fallback: Eclipse project without pom.xml
                    IProject project = importExistingProject(moduleDir, monitor);
                    if (project != null) {
                        imported.add(project);
                    }
                } else {
                    // Module dir exists but has neither pom.xml nor .project
                    McpLogger.warn("ProjectImporter",
                            "Module '" + module + "' has no pom.xml or .project, importing as basic project");
                    IProject project = importBasicJavaProject(moduleDir, monitor);
                    if (project != null) {
                        imported.add(project);
                    }
                }
            }

            // Also import the parent if it has source directories
            if (hasSourceDirectories(projectDir)) {
                IProject parent = createMavenModuleProject(projectDir, projectDir.getFileName().toString(), monitor);
                if (parent != null) {
                    imported.add(parent);
                }
            }
        } else {
            // Single-module Maven project
            IProject project = createMavenModuleProject(projectDir, projectDir.getFileName().toString(), monitor);
            if (project != null) {
                imported.add(project);
            }
        }

        return imported;
    }

    /**
     * Creates a Java project for a Maven module with standard layout.
     */
    private static IProject createMavenModuleProject(Path moduleDir, String projectName, IProgressMonitor monitor) {
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
                project.open(monitor);
                McpLogger.info("ProjectImporter", "Opened existing project: " + projectName);
                return project;
            }

            project.create(description, monitor);
            project.open(monitor);

            // Configure as Java project
            IJavaProject javaProject = JavaCore.create(project);

            List<IClasspathEntry> entries = new ArrayList<>();

            // Add source folders that exist
            addSourceFolderIfExists(project, entries, "src/main/java");
            addSourceFolderIfExists(project, entries, "src/test/java");
            addSourceFolderIfExists(project, entries, "src/main/resources");
            addSourceFolderIfExists(project, entries, "src/test/resources");

            // If no standard Maven dirs found, check for src/ directly
            if (entries.isEmpty()) {
                addSourceFolderIfExists(project, entries, "src");
            }

            // Add JRE container
            entries.add(JavaCore.newContainerEntry(
                    new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

            // Add Maven dependencies from local repository
            addMavenDependencies(moduleDir, entries);

            javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), monitor);

            // Set output location
            org.eclipse.core.runtime.IPath outputPath = project.getFullPath().append("target/classes");
            javaProject.setOutputLocation(outputPath, monitor);

            McpLogger.info("ProjectImporter", "Created Maven project: " + projectName +
                    " with " + entries.size() + " classpath entries");
            return project;

        } catch (Exception e) {
            McpLogger.error("ProjectImporter", "Failed to create Maven project: " + projectName, e);
            return null;
        }
    }

    /**
     * Imports a directory as a basic Java project (no pom.xml, no .project).
     */
    private static IProject importBasicJavaProject(Path projectDir, IProgressMonitor monitor) {
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
                project.open(monitor);
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
     */
    private static void addSourceFolderIfExists(IProject project, List<IClasspathEntry> entries, String folderPath) {
        Path absolutePath = Path.of(project.getLocation().toOSString(), folderPath);
        if (Files.isDirectory(absolutePath)) {
            org.eclipse.core.runtime.IPath srcPath = project.getFullPath().append(folderPath);
            entries.add(JavaCore.newSourceEntry(srcPath));
        }
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
    static void setupInterProjectDependencies(List<IProject> projects, IProgressMonitor monitor) {
        // Build a map of artifactId -> IProject for all workspace projects
        Map<String, IProject> artifactToProject = new HashMap<>();
        for (IProject project : projects) {
            Path projectDir = Path.of(project.getLocation().toOSString());
            Path pomFile = projectDir.resolve("pom.xml");
            if (Files.exists(pomFile)) {
                String artifactId = readMavenArtifactId(pomFile);
                if (artifactId != null) {
                    artifactToProject.put(artifactId, project);
                }
            }
        }

        if (artifactToProject.size() < 2) {
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
                Set<String> existingProjectRefs = new HashSet<>();
                List<IProject> referencedProjects = new ArrayList<>();
                int addedCount = 0;

                // Strategy 1: Replace matching JAR entries with project entries
                for (IClasspathEntry entry : existing) {
                    if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                        String jarName = entry.getPath().lastSegment();
                        IProject matchedProject = findMatchingWorkspaceProject(jarName, artifactToProject, project);
                        if (matchedProject != null) {
                            newClasspath.add(JavaCore.newProjectEntry(matchedProject.getFullPath()));
                            referencedProjects.add(matchedProject);
                            existingProjectRefs.add(matchedProject.getName());
                            addedCount++;
                            continue;
                        }
                    }
                    if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                        String projName = entry.getPath().lastSegment();
                        existingProjectRefs.add(projName);
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
                                && !existingProjectRefs.contains(depProject.getName())) {
                            newClasspath.add(JavaCore.newProjectEntry(depProject.getFullPath()));
                            referencedProjects.add(depProject);
                            existingProjectRefs.add(depProject.getName());
                            addedCount++;
                        }
                    }
                }

                if (addedCount > 0) {
                    javaProject.setRawClasspath(newClasspath.toArray(new IClasspathEntry[0]), monitor);

                    IProjectDescription desc = project.getDescription();
                    desc.setReferencedProjects(referencedProjects.toArray(new IProject[0]));
                    project.setDescription(desc, monitor);

                    totalAdded += addedCount;
                    McpLogger.info("ProjectImporter", "Added " + addedCount +
                            " project dependencies to " + project.getName());
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
     * Checks if a JAR filename matches a workspace project's artifactId.
     * E.g., "culinarygraph-rdf-api-0.0.1-SNAPSHOT.jar" matches project with artifactId "culinarygraph-rdf-api".
     */
    private static IProject findMatchingWorkspaceProject(String jarName,
            Map<String, IProject> artifactToProject, IProject self) {
        for (var entry : artifactToProject.entrySet()) {
            if (jarName.startsWith(entry.getKey() + "-") && !entry.getValue().equals(self)) {
                return entry.getValue();
            }
        }
        return null;
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
    private static List<IProject> scanSubdirectories(Path directory, IProgressMonitor monitor) {
        List<IProject> imported = new ArrayList<>();

        try (var entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path subDir : entries) {
                String name = subDir.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }

                if (Files.exists(subDir.resolve("pom.xml"))) {
                    imported.addAll(importMavenProject(subDir, monitor));
                } else if (isGradleProject(subDir)) {
                    IProject project = importGradleProject(subDir, monitor);
                    if (project != null) {
                        imported.add(project);
                    }
                } else if (Files.exists(subDir.resolve(".project"))) {
                    IProject project = importExistingProject(subDir, monitor);
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
            IProject project = importBasicJavaProject(directory, monitor);
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
        List<IProject> imported = importFromDirectory(path, monitor);

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
    private static IProject importGradleProject(Path projectDir, IProgressMonitor monitor) {
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
                project.open(monitor);
                McpLogger.info("ProjectImporter", "Opened existing Gradle project: " + projectName);
                return project;
            }

            project.create(description, monitor);
            project.open(monitor);

            IJavaProject javaProject = JavaCore.create(project);
            List<IClasspathEntry> entries = new ArrayList<>();

            // Standard Gradle/Maven source layout
            addSourceFolderIfExists(project, entries, "src/main/java");
            addSourceFolderIfExists(project, entries, "src/test/java");
            addSourceFolderIfExists(project, entries, "src/main/resources");
            addSourceFolderIfExists(project, entries, "src/test/resources");
            // Kotlin source dirs
            addSourceFolderIfExists(project, entries, "src/main/kotlin");
            addSourceFolderIfExists(project, entries, "src/test/kotlin");

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
}
