package org.naturzukunft.jdt.mcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
 * Supports existing Eclipse projects (.project) and Maven projects (pom.xml).
 */
public class ProjectImporter {

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

        Path projectFile = directory.resolve(".project");
        Path pomFile = directory.resolve("pom.xml");

        if (Files.exists(projectFile)) {
            // Existing Eclipse project — import as-is
            IProject project = importExistingProject(directory, monitor);
            if (project != null) {
                imported.add(project);
            }
        } else if (Files.exists(pomFile)) {
            // Maven project
            imported.addAll(importMavenProject(directory, monitor));
        } else {
            // Fallback: import as basic Java project
            IProject project = importBasicJavaProject(directory, monitor);
            if (project != null) {
                imported.add(project);
            }
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
                if (Files.isDirectory(moduleDir)) {
                    Path moduleProjectFile = moduleDir.resolve(".project");
                    if (Files.exists(moduleProjectFile)) {
                        IProject project = importExistingProject(moduleDir, monitor);
                        if (project != null) {
                            imported.add(project);
                        }
                    } else if (Files.exists(moduleDir.resolve("pom.xml"))) {
                        // Check if module itself is multi-module (recursive)
                        List<String> subModules = readMavenModules(moduleDir.resolve("pom.xml"));
                        if (!subModules.isEmpty()) {
                            imported.addAll(importMavenProject(moduleDir, monitor));
                        } else {
                            IProject project = createMavenModuleProject(moduleDir, module, monitor);
                            if (project != null) {
                                imported.add(project);
                            }
                        }
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
     * Resolves Maven dependencies using 'mvn dependency:build-classpath' and adds them.
     */
    private static void addMavenDependencies(Path moduleDir, List<IClasspathEntry> entries) {
        try {
            Path cpFile = Files.createTempFile("jdtmcp-classpath-", ".txt");
            cpFile.toFile().deleteOnExit();

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-q", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile.toString(),
                    "-DincludeScope=compile")
                    .directory(moduleDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();

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
                            "Added " + addedCount + " Maven dependencies for " + moduleDir.getFileName());
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
     * Checks if a directory has standard Maven source directories.
     */
    private static boolean hasSourceDirectories(Path dir) {
        return Files.isDirectory(dir.resolve("src/main/java")) ||
                Files.isDirectory(dir.resolve("src/test/java")) ||
                Files.isDirectory(dir.resolve("src"));
    }
}
