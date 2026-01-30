package org.naturzukunft.jdt.mcp.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.naturzukunft.jdt.mcp.McpServerManager.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * MCP tools for executing Java code.
 */
@SuppressWarnings("restriction")
public class ExecutionTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Detects the Maven command to use for a project.
     * Search order:
     * 1. Maven Wrapper in project directory (./mvnw or mvnw.cmd)
     * 2. M2_HOME environment variable
     * 3. MAVEN_HOME environment variable
     * 4. SDKMAN installation (~/.sdkman/candidates/maven/current/bin/mvn)
     * 5. Global 'mvn' command (fallback)
     *
     * @param projectDir the project directory
     * @return the Maven command to use
     */
    private static String detectMavenCommand(File projectDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String mvnExecutable = isWindows ? "mvn.cmd" : "mvn";
        String wrapperExecutable = isWindows ? "mvnw.cmd" : "mvnw";

        // 1. Check for Maven Wrapper in project directory
        File mvnw = new File(projectDir, wrapperExecutable);
        if (mvnw.exists() && mvnw.canExecute()) {
            System.out.println("[JDT MCP] Using Maven Wrapper: " + mvnw.getAbsolutePath());
            return mvnw.getAbsolutePath();
        }

        // 2. Check M2_HOME environment variable
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null && !m2Home.isEmpty()) {
            File m2Maven = new File(m2Home, "bin/" + mvnExecutable);
            if (m2Maven.exists() && m2Maven.canExecute()) {
                System.out.println("[JDT MCP] Using M2_HOME Maven: " + m2Maven.getAbsolutePath());
                return m2Maven.getAbsolutePath();
            }
        }

        // 3. Check MAVEN_HOME environment variable
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isEmpty()) {
            File mavenHomeMvn = new File(mavenHome, "bin/" + mvnExecutable);
            if (mavenHomeMvn.exists() && mavenHomeMvn.canExecute()) {
                System.out.println("[JDT MCP] Using MAVEN_HOME Maven: " + mavenHomeMvn.getAbsolutePath());
                return mavenHomeMvn.getAbsolutePath();
            }
        }

        // 4. Check SDKMAN installation
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File sdkmanMaven = new File(userHome, ".sdkman/candidates/maven/current/bin/" + mvnExecutable);
            if (sdkmanMaven.exists() && sdkmanMaven.canExecute()) {
                System.out.println("[JDT MCP] Using SDKMAN Maven: " + sdkmanMaven.getAbsolutePath());
                return sdkmanMaven.getAbsolutePath();
            }
        }

        // 5. Fallback to global 'mvn' command
        System.out.println("[JDT MCP] Using global Maven command: mvn");
        return "mvn";
    }

    /**
     * Detects the JAVA_HOME for a specific Java version.
     * Search order:
     * 1. SDKMAN candidates (~/.sdkman/candidates/java/)
     * 2. Common installation paths (/usr/lib/jvm/)
     * 3. Current JAVA_HOME as fallback
     *
     * @param javaVersion the required Java version (e.g., "21", "25")
     * @return the JAVA_HOME path or null if not found
     */
    private static String detectJavaHome(String javaVersion) {
        if (javaVersion == null || javaVersion.isEmpty()) {
            return null;
        }

        String userHome = System.getProperty("user.home");

        // 1. Check SDKMAN candidates
        if (userHome != null) {
            File sdkmanJava = new File(userHome, ".sdkman/candidates/java");
            if (sdkmanJava.exists() && sdkmanJava.isDirectory()) {
                File[] candidates = sdkmanJava.listFiles();
                if (candidates != null) {
                    // Look for exact version match first (e.g., "25.0.1-open")
                    for (File candidate : candidates) {
                        if (candidate.isDirectory() && candidate.getName().startsWith(javaVersion + ".")) {
                            File javaExe = new File(candidate, "bin/java");
                            if (javaExe.exists()) {
                                System.out.println("[JDT MCP] Using SDKMAN Java " + javaVersion + ": " + candidate.getAbsolutePath());
                                return candidate.getAbsolutePath();
                            }
                        }
                    }
                    // Look for major version match (e.g., "25-open", "25-graal")
                    for (File candidate : candidates) {
                        if (candidate.isDirectory() && candidate.getName().startsWith(javaVersion + "-")) {
                            File javaExe = new File(candidate, "bin/java");
                            if (javaExe.exists()) {
                                System.out.println("[JDT MCP] Using SDKMAN Java " + javaVersion + ": " + candidate.getAbsolutePath());
                                return candidate.getAbsolutePath();
                            }
                        }
                    }
                }
            }
        }

        // 2. Check common Linux installation paths
        File usrLibJvm = new File("/usr/lib/jvm");
        if (usrLibJvm.exists() && usrLibJvm.isDirectory()) {
            File[] jvms = usrLibJvm.listFiles();
            if (jvms != null) {
                for (File jvm : jvms) {
                    if (jvm.isDirectory() &&
                        (jvm.getName().contains("java-" + javaVersion) ||
                         jvm.getName().contains("jdk-" + javaVersion))) {
                        File javaExe = new File(jvm, "bin/java");
                        if (javaExe.exists()) {
                            System.out.println("[JDT MCP] Using system Java " + javaVersion + ": " + jvm.getAbsolutePath());
                            return jvm.getAbsolutePath();
                        }
                    }
                }
            }
        }

        // 3. No specific version found
        System.out.println("[JDT MCP] Java " + javaVersion + " not found, using system default");
        return null;
    }

    /**
     * Configures the ProcessBuilder environment with the correct JAVA_HOME for the project.
     *
     * @param pb the ProcessBuilder to configure
     * @param project the Eclipse project
     */
    private static void configureJavaEnvironment(ProcessBuilder pb, IProject project) {
        try {
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject != null && javaProject.exists()) {
                String javaVersion = javaProject.getOption(JavaCore.COMPILER_SOURCE, true);
                if (javaVersion != null) {
                    String javaHome = detectJavaHome(javaVersion);
                    if (javaHome != null) {
                        Map<String, String> env = pb.environment();
                        env.put("JAVA_HOME", javaHome);
                        System.out.println("[JDT MCP] Set JAVA_HOME=" + javaHome + " for project Java " + javaVersion);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[JDT MCP] Could not configure Java environment: " + e.getMessage());
        }
    }

    /**
     * Tool: Run Maven build.
     */
    public static ToolRegistration mavenBuildTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "goals", Map.of(
                                "type", "string",
                                "description", "Maven goals: 'clean' (delete target), 'compile' (build), 'test' (run tests), 'package' (create JAR), 'install' (to local repo), 'verify' (integration tests). Can combine: 'clean package'"),
                        "profiles", Map.of(
                                "type", "string",
                                "description", "Maven profiles to activate (optional, comma-separated)"),
                        "skipTests", Map.of(
                                "type", "boolean",
                                "description", "Skip test execution (default: false)"),
                        "offline", Map.of(
                                "type", "boolean",
                                "description", "Run Maven in offline mode (default: false)"),
                        "timeoutSeconds", Map.of(
                                "type", "integer",
                                "description", "Timeout in seconds (default: 300)")),
                List.of("projectName", "goals"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_maven_build",
                "🤖 PREFERRED over Bash for builds. Run Maven with Eclipse-aware compilation. " +
                "WHY USE THIS: Auto-detects Maven Wrapper (./mvnw), returns structured output, " +
                "integrates with jdt_get_compilation_errors for follow-up analysis. " +
                "Common workflows: 'clean compile' (fresh build), 'clean package' (create JAR/WAR), " +
                "'clean install' (build + install to local repo). " +
                "WORKFLOW TIP: After build, use jdt_get_compilation_errors to see remaining issues.",
                schema,
                null);

        return new ToolRegistration(tool, args -> runMavenBuild(
                (String) args.get("projectName"),
                (String) args.get("goals"),
                (String) args.get("profiles"),
                args.get("skipTests") != null ? (Boolean) args.get("skipTests") : false,
                args.get("offline") != null ? (Boolean) args.get("offline") : false,
                args.get("timeoutSeconds") != null ? ((Number) args.get("timeoutSeconds")).intValue() : 300));
    }

    private static CallToolResult runMavenBuild(String projectName, String goals, String profiles,
            boolean skipTests, boolean offline, int timeoutSeconds) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            // Build Maven command
            List<String> command = new ArrayList<>();
            command.add(detectMavenCommand(project.getLocation().toFile()));

            // Add goals
            for (String goal : goals.split("\\s+")) {
                if (!goal.isEmpty()) {
                    command.add(goal);
                }
            }

            // Add profiles
            if (profiles != null && !profiles.isEmpty()) {
                command.add("-P" + profiles);
            }

            // Skip tests flag
            if (skipTests) {
                command.add("-DskipTests");
            }

            // Offline mode
            if (offline) {
                command.add("-o");
            }

            // Point to pom.xml
            command.add("-f");
            command.add(project.getLocation().toString() + "/pom.xml");

            System.out.println("[JDT MCP] Running Maven: " + String.join(" ", command));

            // Execute
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(project.getLocation().toFile());
            pb.redirectErrorStream(true);

            // Configure JAVA_HOME based on project's Java version
            configureJavaEnvironment(pb, project);

            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("goals", goals);
            result.put("profiles", profiles);
            result.put("skipTests", skipTests);
            result.put("offline", offline);

            if (!finished) {
                process.destroyForcibly();
                result.put("status", "TIMEOUT");
                result.put("message", "Maven build timed out after " + timeoutSeconds + " seconds");
                result.put("output", output.toString());
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            int exitCode = process.exitValue();
            result.put("exitCode", exitCode);
            result.put("output", output.toString());

            // Parse build result from output
            String outputStr = output.toString();
            if (outputStr.contains("BUILD SUCCESS")) {
                result.put("status", "SUCCESS");
                result.put("message", "Maven build completed successfully");
            } else if (outputStr.contains("BUILD FAILURE")) {
                result.put("status", "FAILURE");
                result.put("message", "Maven build failed");
            } else {
                result.put("status", exitCode == 0 ? "SUCCESS" : "ERROR");
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), exitCode != 0);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error running Maven build: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error running Maven build: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: Run a Java class with main method.
     */
    public static ToolRegistration runMainTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "className", Map.of(
                                "type", "string",
                                "description", "Fully qualified name of class with main(String[] args) method (e.g., 'com.example.Main'). Get from jdt_find_type."),
                        "args", Map.of(
                                "type", "string",
                                "description", "Command line arguments passed to main(), space-separated (e.g., '--config prod --verbose')"),
                        "timeoutSeconds", Map.of(
                                "type", "integer",
                                "description", "Max execution time before killing process (default: 30)")),
                List.of("className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_run_main",
                "Execute a Java class with main() method and capture stdout/stderr. " +
                "NOTE: Project must be compiled first (use jdt_maven_build with 'compile' goal). " +
                "Returns exit code and all output.",
                schema,
                null);

        return new ToolRegistration(tool, args -> runMain(
                (String) args.get("className"),
                (String) args.get("args"),
                args.get("timeoutSeconds") != null ? ((Number) args.get("timeoutSeconds")).intValue() : 30));
    }

    private static CallToolResult runMain(String className, String cmdArgs, int timeoutSeconds) {
        try {
            // Find the type and its project
            IType type = null;
            IJavaProject javaProject = null;

            for (IJavaProject project : JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProjects()) {
                type = project.findType(className);
                if (type != null) {
                    javaProject = project;
                    break;
                }
            }

            if (type == null || javaProject == null) {
                return new CallToolResult("Class not found: " + className, true);
            }

            // Build classpath
            List<String> classpathEntries = new ArrayList<>();
            File projectDir = javaProject.getProject().getLocation().toFile();

            // Add main output location (e.g., target/classes)
            // getOutputLocation() returns workspace-relative path like /project-name/target/classes
            org.eclipse.core.runtime.IPath outputLocation = javaProject.getOutputLocation();
            String relativeOutput = outputLocation.removeFirstSegments(1).toString();
            File outputDir = new File(projectDir, relativeOutput);
            if (outputDir.exists()) {
                classpathEntries.add(outputDir.getAbsolutePath());
            }

            // Add source folder specific output locations and library entries
            for (IClasspathEntry entry : javaProject.getResolvedClasspath(true)) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    // Source folders may have custom output locations
                    org.eclipse.core.runtime.IPath sourceOutput = entry.getOutputLocation();
                    if (sourceOutput != null) {
                        String relSourceOutput = sourceOutput.removeFirstSegments(1).toString();
                        File sourceOutputDir = new File(projectDir, relSourceOutput);
                        if (sourceOutputDir.exists() && !classpathEntries.contains(sourceOutputDir.getAbsolutePath())) {
                            classpathEntries.add(sourceOutputDir.getAbsolutePath());
                        }
                    }
                } else if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                    org.eclipse.core.runtime.IPath libPath = entry.getPath();
                    File libFile = libPath.toFile();
                    // Check if absolute or needs resolution
                    if (!libFile.exists()) {
                        // Try workspace-relative resolution
                        libFile = new File(projectDir, libPath.removeFirstSegments(1).toString());
                    }
                    if (libFile.exists()) {
                        classpathEntries.add(libFile.getAbsolutePath());
                    }
                }
            }

            String classpath = String.join(System.getProperty("path.separator"), classpathEntries);

            // Build command
            List<String> command = new ArrayList<>();
            command.add("java");
            command.add("-cp");
            command.add(classpath.toString());
            command.add(className);

            if (cmdArgs != null && !cmdArgs.isEmpty()) {
                for (String arg : cmdArgs.split("\\s+")) {
                    command.add(arg);
                }
            }

            System.out.println("[JDT MCP] Running: " + String.join(" ", command));

            // Execute
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(javaProject.getProject().getLocation().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            result.put("className", className);
            result.put("args", cmdArgs);

            if (!finished) {
                process.destroyForcibly();
                result.put("status", "TIMEOUT");
                result.put("message", "Process timed out after " + timeoutSeconds + " seconds");
                result.put("output", output.toString());
                return new CallToolResult(MAPPER.writeValueAsString(result), true);
            }

            int exitCode = process.exitValue();
            result.put("exitCode", exitCode);
            result.put("output", output.toString());

            if (exitCode == 0) {
                result.put("status", "SUCCESS");
            } else {
                result.put("status", "ERROR");
                result.put("message", "Process exited with code " + exitCode);
            }

            return new CallToolResult(MAPPER.writeValueAsString(result), exitCode != 0);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error running class: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error running class: " + e.getMessage(), true);
            }
        }
    }

    /**
     * Tool: List tests in a project.
     */
    public static ToolRegistration listTestsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Optional filter pattern: 'unit' (*Test.java), 'integration' (*IT.java), or custom glob pattern. Omit to list all tests.")),
                List.of("projectName"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_list_tests",
                "Find all test classes in a project. Returns class names, @Test methods, and file paths. " +
                "Use pattern='unit' for *Test.java, pattern='integration' for *IT.java, or omit for all tests.",
                schema,
                null);

        return new ToolRegistration(tool, args -> listTests(
                (String) args.get("projectName"),
                (String) args.get("pattern")));
    }

    private static CallToolResult listTests(String projectName, String pattern) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                return new CallToolResult("Not a Java project: " + projectName, true);
            }

            List<Map<String, Object>> tests = new ArrayList<>();

            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                String name = cu.getElementName();

                                // Check if this is a test class based on pattern
                                boolean isTest = false;
                                String testType = null;

                                if (name.endsWith("IT.java") || name.endsWith("IntegrationTest.java")) {
                                    isTest = true;
                                    testType = "integration";
                                } else if (name.endsWith("Test.java") || name.startsWith("Test")) {
                                    isTest = true;
                                    testType = "unit";
                                }

                                // Apply filter
                                if (pattern != null && !pattern.isEmpty()) {
                                    if (pattern.equals("unit") && !"unit".equals(testType)) {
                                        isTest = false;
                                    } else if (pattern.equals("integration") && !"integration".equals(testType)) {
                                        isTest = false;
                                    }
                                }

                                if (isTest) {
                                    for (IType type : cu.getTypes()) {
                                        Map<String, Object> testInfo = new HashMap<>();
                                        testInfo.put("className", type.getFullyQualifiedName());
                                        testInfo.put("simpleName", type.getElementName());
                                        testInfo.put("package", pkg.getElementName());
                                        testInfo.put("testType", testType);

                                        // Count test methods
                                        int testMethodCount = 0;
                                        List<String> testMethods = new ArrayList<>();
                                        for (IMethod method : type.getMethods()) {
                                            for (var annotation : method.getAnnotations()) {
                                                String annotationName = annotation.getElementName();
                                                if (annotationName.equals("Test") || annotationName.equals("org.junit.Test")
                                                        || annotationName.equals("org.junit.jupiter.api.Test")) {
                                                    testMethodCount++;
                                                    testMethods.add(method.getElementName());
                                                    break;
                                                }
                                            }
                                        }
                                        testInfo.put("testMethodCount", testMethodCount);
                                        testInfo.put("testMethods", testMethods);

                                        if (cu.getResource() != null) {
                                            testInfo.put("file", cu.getResource().getLocation().toString());
                                        }

                                        tests.add(testInfo);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("pattern", pattern);
            result.put("testCount", tests.size());
            result.put("tests", tests);

            return new CallToolResult(MAPPER.writeValueAsString(result), false);

        } catch (Exception e) {
            return new CallToolResult("Error listing tests: " + e.getMessage(), true);
        }
    }

    /**
     * Tool: Run tests using Eclipse JUnit Runner.
     */
    public static ToolRegistration runTestsTool() {
        JsonSchema schema = new JsonSchema(
                "object",
                Map.of(
                        "projectName", Map.of(
                                "type", "string",
                                "description", "Eclipse project name (get from jdt_list_projects)"),
                        "className", Map.of(
                                "type", "string",
                                "description", "Test class to run (simple name like 'UserServiceTest' or fully qualified). Required."),
                        "methodName", Map.of(
                                "type", "string",
                                "description", "Run only this test method (e.g., 'testFindById'). Omit to run all tests in class."),
                        "timeoutSeconds", Map.of(
                                "type", "integer",
                                "description", "Max execution time (default: 120)")),
                List.of("projectName", "className"),
                null, null, null);

        Tool tool = new Tool(
                "jdt_run_tests",
                "🤖 PREFERRED over Bash for testing. Run tests using Eclipse's JUnit runner. " +
                "WHY USE THIS: Uses project's Java version automatically, no Maven configuration needed, " +
                "returns structured JSON with test results including failures with file paths and line numbers. " +
                "Works for both unit tests (*Test) and integration tests (*IT) - no distinction needed. " +
                "WORKFLOW TIP: Use jdt_list_tests first to discover available tests.",
                schema,
                null);

        return new ToolRegistration(tool, args -> runTestsWithJUnit(
                (String) args.get("projectName"),
                (String) args.get("className"),
                (String) args.get("methodName"),
                args.get("timeoutSeconds") != null ? ((Number) args.get("timeoutSeconds")).intValue() : 120));
    }

    private static CallToolResult runTestsWithJUnit(String projectName, String className, String methodName, int timeoutSeconds) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return new CallToolResult("Project not found: " + projectName, true);
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                return new CallToolResult("Not a Java project: " + projectName, true);
            }

            // Find the test type
            IType testType = null;

            // Try as fully qualified name first
            testType = javaProject.findType(className);

            // If not found, search by simple name
            if (testType == null) {
                for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                    if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                        for (IJavaElement child : root.getChildren()) {
                            if (child instanceof IPackageFragment pkg) {
                                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                    for (IType type : cu.getTypes()) {
                                        if (type.getElementName().equals(className)) {
                                            testType = type;
                                            break;
                                        }
                                    }
                                    if (testType != null) break;
                                }
                                if (testType != null) break;
                            }
                        }
                        if (testType != null) break;
                    }
                }
            }

            if (testType == null) {
                return new CallToolResult("Test class not found: " + className, true);
            }

            String fullyQualifiedName = testType.getFullyQualifiedName();

            // Create JUnit launch configuration
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType junitType = launchManager.getLaunchConfigurationType(
                    "org.eclipse.jdt.junit.launchconfig");

            if (junitType == null) {
                return new CallToolResult("JUnit launch configuration type not found. Is JUnit plugin installed?", true);
            }

            String configName = "MCP-Test-" + testType.getElementName() + "-" + System.currentTimeMillis();
            ILaunchConfigurationWorkingCopy workingCopy = junitType.newInstance(null, configName);

            // Configure the launch
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, fullyQualifiedName);
            workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", "");
            workingCopy.setAttribute("org.eclipse.jdt.junit.TESTNAME", methodName != null ? methodName : "");
            workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");

            // Set up result collection
            final List<Map<String, Object>> testResults = new ArrayList<>();
            final int[] counts = new int[4]; // [total, passed, failed, errors]
            final CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder errorOutput = new StringBuilder();

            TestRunListener listener = new TestRunListener() {
                @Override
                public void sessionFinished(ITestRunSession session) {
                    counts[0] = session.getStartedCount();
                    counts[1] = session.getStartedCount() - session.getFailureCount() - session.getErrorCount();
                    counts[2] = session.getFailureCount();
                    counts[3] = session.getErrorCount();
                    latch.countDown();
                }

                @Override
                public void testCaseFinished(ITestCaseElement testCaseElement) {
                    Map<String, Object> testResult = new HashMap<>();
                    testResult.put("className", testCaseElement.getTestClassName());
                    testResult.put("methodName", testCaseElement.getTestMethodName());

                    ITestElement.Result result = testCaseElement.getTestResult(false);
                    testResult.put("status", result.toString());

                    if (result == ITestElement.Result.FAILURE || result == ITestElement.Result.ERROR) {
                        String trace = testCaseElement.getFailureTrace() != null ?
                                testCaseElement.getFailureTrace().getTrace() : null;
                        if (trace != null) {
                            testResult.put("failureTrace", trace);
                            // Extract line number from stack trace
                            java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile(
                                    testCaseElement.getTestClassName().replace(".", "\\.") +
                                    "\\." + testCaseElement.getTestMethodName() + "\\(.*:(\\d+)\\)");
                            java.util.regex.Matcher matcher = linePattern.matcher(trace);
                            if (matcher.find()) {
                                testResult.put("line", Integer.parseInt(matcher.group(1)));
                            }
                        }
                    }

                    testResult.put("duration", testCaseElement.getElapsedTimeInSeconds());
                    testResults.add(testResult);
                }
            };

            JUnitCore.addTestRunListener(listener);

            try {
                // Launch the tests
                ILaunchConfiguration config = workingCopy.doSave();
                ILaunch launch = config.launch(ILaunchManager.RUN_MODE, null, false);

                // Wait for completion with timeout
                boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);

                // Clean up
                if (!completed) {
                    // Terminate if still running
                    for (IProcess process : launch.getProcesses()) {
                        if (!process.isTerminated()) {
                            process.terminate();
                        }
                    }
                }

                // Delete temporary launch config
                config.delete();

                // Build result
                Map<String, Object> result = new HashMap<>();
                result.put("projectName", projectName);
                result.put("className", fullyQualifiedName);
                result.put("methodName", methodName);

                if (!completed) {
                    result.put("status", "TIMEOUT");
                    result.put("message", "Tests timed out after " + timeoutSeconds + " seconds");
                    return new CallToolResult(MAPPER.writeValueAsString(result), true);
                }

                result.put("testsRun", counts[0]);
                result.put("passed", counts[1]);
                result.put("failures", counts[2]);
                result.put("errors", counts[3]);
                result.put("testResults", testResults);

                if (counts[2] == 0 && counts[3] == 0) {
                    result.put("status", "SUCCESS");
                    result.put("message", "All " + counts[0] + " tests passed");
                } else {
                    result.put("status", "FAILURE");
                    result.put("message", counts[2] + " failures, " + counts[3] + " errors out of " + counts[0] + " tests");
                }

                return new CallToolResult(MAPPER.writeValueAsString(result), counts[2] > 0 || counts[3] > 0);

            } finally {
                JUnitCore.removeTestRunListener(listener);
            }

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Error running tests: " + e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception ex) {
                return new CallToolResult("Error running tests: " + e.getMessage(), true);
            }
        }
    }
}
