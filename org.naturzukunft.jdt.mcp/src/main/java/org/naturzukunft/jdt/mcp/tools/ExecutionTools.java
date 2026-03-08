package org.naturzukunft.jdt.mcp.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.eclipse.debug.core.model.IStreamsProxy;
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
import org.naturzukunft.jdt.mcp.McpLogger;
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
            System.err.println("[JDT MCP] Using Maven Wrapper: " + mvnw.getAbsolutePath());
            return mvnw.getAbsolutePath();
        }

        // 2. Check M2_HOME environment variable
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null && !m2Home.isEmpty()) {
            File m2Maven = new File(m2Home, "bin/" + mvnExecutable);
            if (m2Maven.exists() && m2Maven.canExecute()) {
                System.err.println("[JDT MCP] Using M2_HOME Maven: " + m2Maven.getAbsolutePath());
                return m2Maven.getAbsolutePath();
            }
        }

        // 3. Check MAVEN_HOME environment variable
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isEmpty()) {
            File mavenHomeMvn = new File(mavenHome, "bin/" + mvnExecutable);
            if (mavenHomeMvn.exists() && mavenHomeMvn.canExecute()) {
                System.err.println("[JDT MCP] Using MAVEN_HOME Maven: " + mavenHomeMvn.getAbsolutePath());
                return mavenHomeMvn.getAbsolutePath();
            }
        }

        // 4. Check SDKMAN installation
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File sdkmanMaven = new File(userHome, ".sdkman/candidates/maven/current/bin/" + mvnExecutable);
            if (sdkmanMaven.exists() && sdkmanMaven.canExecute()) {
                System.err.println("[JDT MCP] Using SDKMAN Maven: " + sdkmanMaven.getAbsolutePath());
                return sdkmanMaven.getAbsolutePath();
            }
        }

        // 5. Fallback to global 'mvn' command
        System.err.println("[JDT MCP] Using global Maven command: mvn");
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
                                System.err.println("[JDT MCP] Using SDKMAN Java " + javaVersion + ": " + candidate.getAbsolutePath());
                                return candidate.getAbsolutePath();
                            }
                        }
                    }
                    // Look for major version match (e.g., "25-open", "25-graal")
                    for (File candidate : candidates) {
                        if (candidate.isDirectory() && candidate.getName().startsWith(javaVersion + "-")) {
                            File javaExe = new File(candidate, "bin/java");
                            if (javaExe.exists()) {
                                System.err.println("[JDT MCP] Using SDKMAN Java " + javaVersion + ": " + candidate.getAbsolutePath());
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
                            System.err.println("[JDT MCP] Using system Java " + javaVersion + ": " + jvm.getAbsolutePath());
                            return jvm.getAbsolutePath();
                        }
                    }
                }
            }
        }

        // 3. No specific version found
        System.err.println("[JDT MCP] Java " + javaVersion + " not found, using system default");
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
                        System.err.println("[JDT MCP] Set JAVA_HOME=" + javaHome + " for project Java " + javaVersion);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JDT MCP] Could not configure Java environment: " + e.getMessage());
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

        return new ToolRegistration(tool, (args, progress) -> runMavenBuild(
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

            System.err.println("[JDT MCP] Running Maven: " + String.join(" ", command));

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
            return ToolErrors.errorResult("maven build", e);
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

        return new ToolRegistration(tool, (args, progress) -> runMain(
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
                } else if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                    // Add dependent project's output directory to runtime classpath
                    String depProjectName = entry.getPath().lastSegment();
                    IProject depProject = ResourcesPlugin.getWorkspace().getRoot().getProject(depProjectName);
                    if (depProject.exists()) {
                        IJavaProject depJavaProject = JavaCore.create(depProject);
                        if (depJavaProject != null && depJavaProject.exists()) {
                            org.eclipse.core.runtime.IPath depOutput = depJavaProject.getOutputLocation();
                            String relDepOutput = depOutput.removeFirstSegments(1).toString();
                            File depOutputDir = new File(depProject.getLocation().toFile(), relDepOutput);
                            if (depOutputDir.exists() && !classpathEntries.contains(depOutputDir.getAbsolutePath())) {
                                classpathEntries.add(depOutputDir.getAbsolutePath());
                            }
                        }
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

            System.err.println("[JDT MCP] Running: " + String.join(" ", command));

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
            return ToolErrors.errorResult("run main", e);
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

        return new ToolRegistration(tool, (args, progress) -> listTests(
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

                                        // Count test methods (including @Nested inner classes)
                                        List<String> testMethods = new ArrayList<>();
                                        collectTestMethods(type, testMethods);
                                        testInfo.put("testMethodCount", testMethods.size());
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
            return ToolErrors.errorResult("list tests", e);
        }
    }

    /**
     * Recursively collects test methods from a type and its @Nested inner classes.
     * This supports JUnit 5's nested test feature where tests can be organized
     * in inner classes annotated with @Nested.
     */
    private static void collectTestMethods(IType type, List<String> testMethods) throws Exception {
        // Collect test methods from this type
        for (IMethod method : type.getMethods()) {
            for (var annotation : method.getAnnotations()) {
                String annotationName = annotation.getElementName();
                if (annotationName.equals("Test") || annotationName.equals("org.junit.Test")
                        || annotationName.equals("org.junit.jupiter.api.Test")) {
                    testMethods.add(method.getElementName());
                    break;
                }
            }
        }

        // Recursively check @Nested inner classes (JUnit 5 feature)
        for (IType innerType : type.getTypes()) {
            for (var annotation : innerType.getAnnotations()) {
                String annotationName = annotation.getElementName();
                if (annotationName.equals("Nested") || annotationName.equals("org.junit.jupiter.api.Nested")) {
                    collectTestMethods(innerType, testMethods);
                    break;
                }
            }
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
                "⚠️ HARD LIMIT: MCP client timeout is 60s - use jdt_start_tests_async for tests >30s! " +
                "The timeoutSeconds parameter does NOT override this limit. " +
                "WORKFLOW TIP: Use jdt_list_tests first to discover available tests.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> runTestsWithJUnit(
                (String) args.get("projectName"),
                (String) args.get("className"),
                (String) args.get("methodName"),
                args.get("timeoutSeconds") != null ? ((Number) args.get("timeoutSeconds")).intValue() : 120,
                progress));
    }

    private static CallToolResult runTestsWithJUnit(String projectName, String className, String methodName,
            int timeoutSeconds, org.naturzukunft.jdt.mcp.server.ProgressReporter progress) {
        final String LOG_TAG = "RunTests";
        McpLogger.info(LOG_TAG, "=== Starting test run ===");
        McpLogger.info(LOG_TAG, "Project: " + projectName + ", Class: " + className +
                ", Method: " + (methodName != null ? methodName : "(all)") + ", Timeout: " + timeoutSeconds + "s");

        // Heartbeat mechanism to prevent MCP client timeout (Claude Code has 60s timeout)
        // Declared here so it can be stopped in catch block
        final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        final long HEARTBEAT_INTERVAL_SECONDS = 15;

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

            // If a specific method is requested, check if it exists in the class or @Nested inner classes
            if (methodName != null && !methodName.isEmpty()) {
                IType targetType = findTypeContainingMethod(testType, methodName);
                if (targetType != null && targetType != testType) {
                    // Method found in a nested class - update to use that class
                    testType = targetType;
                    fullyQualifiedName = testType.getFullyQualifiedName();
                } else if (targetType == null) {
                    // Method not found anywhere - provide helpful error
                    List<String> nestedClasses = new ArrayList<>();
                    for (IType nested : testType.getTypes()) {
                        if (hasNestedAnnotation(nested)) {
                            nestedClasses.add(nested.getElementName());
                        }
                    }
                    String errorMsg = "Method '" + methodName + "' not found in class " + className;
                    if (!nestedClasses.isEmpty()) {
                        errorMsg += ". Note: This class has @Nested inner classes: " + String.join(", ", nestedClasses) +
                            ". Try using the fully qualified name like: " + className + "$" + nestedClasses.get(0);
                    }
                    return new CallToolResult(errorMsg, true);
                }
            }

            // Check for Spring Boot integration test annotations and warn
            boolean isIntegrationTest = detectIntegrationTest(testType);
            String integrationTestWarning = null;
            if (isIntegrationTest) {
                integrationTestWarning = "WARNING: This appears to be a Spring Boot integration test (@SpringBootTest or similar). " +
                    "Integration tests may require Spring ApplicationContext, Testcontainers, or external resources. " +
                    "If tests fail silently or timeout, consider using Maven Failsafe: " +
                    "./mvnw failsafe:integration-test -Dit.test=" + testType.getElementName();
            }

            // Create JUnit launch configuration
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType junitType = launchManager.getLaunchConfigurationType(
                    "org.eclipse.jdt.junit.launchconfig");

            if (junitType == null) {
                return new CallToolResult("JUnit launch configuration type not found. Is JUnit plugin installed?", true);
            }

            String configName = "MCP-Test-" + testType.getElementName() + "-" + System.currentTimeMillis();
            McpLogger.info(LOG_TAG, "Creating launch configuration: " + configName);
            ILaunchConfigurationWorkingCopy workingCopy = junitType.newInstance(null, configName);

            // Configure the launch
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, fullyQualifiedName);
            workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");

            // Set working directory to project root - important for Spring Boot to find application.properties
            String projectPath = project.getLocation().toOSString();
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, projectPath);

            // Only set TESTNAME if a specific method is requested
            if (methodName != null && !methodName.isEmpty()) {
                workingCopy.setAttribute("org.eclipse.jdt.junit.TESTNAME", methodName);
            }

            // Count expected tests for progress reporting
            List<String> expectedTests = new ArrayList<>();
            collectTestMethods(testType, expectedTests);
            final int expectedTotal = methodName != null ? 1 : expectedTests.size();

            // Set up result collection with diagnostic state
            final List<Map<String, Object>> testResults = new ArrayList<>();
            final int[] counts = new int[4]; // [total, passed, failed, errors]
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] sessionState = new boolean[4]; // [sessionLaunched, sessionStarted, sessionFinished, hasError]
            final String[] errorMessage = new String[1];
            final int[] progressCounter = new int[1]; // for tracking progress
            final String expectedConfigName = configName; // for session filtering
            final AtomicInteger heartbeatCounter = new AtomicInteger(0);

            TestRunListener listener = new TestRunListener() {

                private boolean isOurSession(ITestRunSession session) {
                    // Filter events to only handle our specific test session
                    String sessionName = session.getTestRunName();
                    return sessionName != null && sessionName.contains(expectedConfigName);
                }

                @Override
                public void sessionLaunched(ITestRunSession session) {
                    McpLogger.debug(LOG_TAG, "sessionLaunched callback - session: " + session.getTestRunName());
                    if (!isOurSession(session)) {
                        McpLogger.debug(LOG_TAG, "Ignoring foreign session");
                        return;
                    }
                    try {
                        sessionState[0] = true; // sessionLaunched
                        McpLogger.info(LOG_TAG, "Test session launched");
                        progress.report(0, expectedTotal, "Test session launched, initializing...");
                    } catch (Exception e) {
                        sessionState[3] = true; // hasError
                        errorMessage[0] = "Error in sessionLaunched: " + e.getMessage();
                        McpLogger.error(LOG_TAG, "Error in sessionLaunched", e);
                    }
                }

                @Override
                public void sessionStarted(ITestRunSession session) {
                    McpLogger.debug(LOG_TAG, "sessionStarted callback - session: " + session.getTestRunName());
                    if (!isOurSession(session)) return;
                    try {
                        sessionState[1] = true; // sessionStarted
                        McpLogger.info(LOG_TAG, "Test session started - test tree initialized");
                        progress.report(0, expectedTotal, "Test session started");
                    } catch (Exception e) {
                        sessionState[3] = true; // hasError
                        errorMessage[0] = "Error in sessionStarted: " + e.getMessage();
                        McpLogger.error(LOG_TAG, "Error in sessionStarted", e);
                    }
                }

                @Override
                public void sessionFinished(ITestRunSession session) {
                    McpLogger.debug(LOG_TAG, "sessionFinished callback - session: " + session.getTestRunName());
                    if (!isOurSession(session)) return;
                    try {
                        sessionState[2] = true; // sessionFinished
                        // Calculate counts from collected results
                        counts[0] = testResults.size(); // total
                        counts[1] = (int) testResults.stream()
                                .filter(r -> "OK".equals(r.get("status")))
                                .count(); // passed
                        counts[2] = (int) testResults.stream()
                                .filter(r -> "FAILURE".equals(r.get("status")))
                                .count(); // failures
                        counts[3] = (int) testResults.stream()
                                .filter(r -> "ERROR".equals(r.get("status")))
                                .count(); // errors
                        McpLogger.info(LOG_TAG, "Test session finished - Total: " + counts[0] +
                                ", Passed: " + counts[1] + ", Failed: " + counts[2] + ", Errors: " + counts[3]);
                        progress.report(counts[0], expectedTotal, "Tests completed: " + counts[1] + " passed, " + counts[2] + " failed");
                    } catch (Exception e) {
                        sessionState[3] = true; // hasError
                        errorMessage[0] = "Error in sessionFinished: " + e.getMessage();
                        McpLogger.error(LOG_TAG, "Error in sessionFinished", e);
                    } finally {
                        McpLogger.debug(LOG_TAG, "Counting down latch");
                        latch.countDown();
                    }
                }

                @Override
                public void testCaseFinished(ITestCaseElement testCaseElement) {
                    // Only process if our session was launched (testCaseElement has no session reference)
                    if (!sessionState[0]) return;
                    McpLogger.debug(LOG_TAG, "testCaseFinished: " + testCaseElement.getTestClassName() +
                            "#" + testCaseElement.getTestMethodName());
                    try {
                        Map<String, Object> testResult = new HashMap<>();
                        testResult.put("className", testCaseElement.getTestClassName());
                        testResult.put("methodName", testCaseElement.getTestMethodName());

                        ITestElement.Result result = testCaseElement.getTestResult(false);
                        testResult.put("status", result.toString());

                        // Send progress notification
                        progressCounter[0]++;
                        String statusIcon = result == ITestElement.Result.OK ? "✓" : "✗";
                        progress.report(progressCounter[0], expectedTotal,
                                statusIcon + " " + testCaseElement.getTestMethodName() + ": " + result);

                        if (result == ITestElement.Result.FAILURE || result == ITestElement.Result.ERROR) {
                            String trace = testCaseElement.getFailureTrace() != null ?
                                    testCaseElement.getFailureTrace().getTrace() : null;
                            if (trace != null) {
                                // Truncate stack trace to keep responses manageable
                                String truncatedTrace = truncateStackTrace(trace, testCaseElement.getTestClassName(), 1500);
                                testResult.put("failureTrace", truncatedTrace);
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
                    } catch (Exception e) {
                        sessionState[3] = true; // hasError
                        errorMessage[0] = "Error in testCaseFinished: " + e.getMessage();
                    }
                }
            };

            JUnitCore.addTestRunListener(listener);

            try {
                // Launch the tests
                McpLogger.info(LOG_TAG, "Saving launch configuration...");
                ILaunchConfiguration config = workingCopy.doSave();
                McpLogger.info(LOG_TAG, "Launching tests in RUN_MODE...");
                ILaunch launch = config.launch(ILaunchManager.RUN_MODE, null, false);

                // Wait for process to start (max 10 seconds)
                McpLogger.debug(LOG_TAG, "Waiting for process to start...");
                int waitCount = 0;
                while (launch.getProcesses().length == 0 && waitCount < 100) {
                    Thread.sleep(100);
                    waitCount++;
                }
                McpLogger.debug(LOG_TAG, "Process wait completed after " + (waitCount * 100) + "ms, processes: " + launch.getProcesses().length);

                if (launch.getProcesses().length == 0) {
                    McpLogger.error(LOG_TAG, "No process started after 10 seconds");
                    config.delete();
                    Map<String, Object> result = new HashMap<>();
                    result.put("projectName", projectName);
                    result.put("className", fullyQualifiedName);
                    result.put("status", "ERROR");
                    result.put("message", "Failed to start test process. This may indicate a classpath or configuration issue.");
                    if (integrationTestWarning != null) {
                        result.put("warning", integrationTestWarning);
                    }
                    return new CallToolResult(MAPPER.writeValueAsString(result), true);
                }

                // Start heartbeat to prevent MCP client timeout
                // Claude Code has a 60s timeout for MCP tool calls, so we send progress every 15s
                McpLogger.info(LOG_TAG, "Starting heartbeat (every " + HEARTBEAT_INTERVAL_SECONDS + "s)...");
                heartbeatExecutor.scheduleAtFixedRate(() -> {
                    try {
                        int count = heartbeatCounter.incrementAndGet();
                        String phase = sessionState[1] ? "Running tests" : "Initializing (Spring context, Testcontainers, etc.)";
                        String message = phase + "... (" + (count * HEARTBEAT_INTERVAL_SECONDS) + "s elapsed)";
                        McpLogger.debug(LOG_TAG, "Heartbeat #" + count + ": " + message);
                        progress.report(progressCounter[0], expectedTotal, message);
                    } catch (Exception e) {
                        McpLogger.warn(LOG_TAG, "Heartbeat failed: " + e.getMessage());
                    }
                }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

                // Wait for completion with timeout
                McpLogger.info(LOG_TAG, "Waiting for test completion (timeout: " + timeoutSeconds + "s)...");
                boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
                McpLogger.info(LOG_TAG, "Latch await completed: " + (completed ? "SUCCESS" : "TIMEOUT"));

                // Check process termination state
                boolean processTerminated = true;
                for (IProcess process : launch.getProcesses()) {
                    if (!process.isTerminated()) {
                        processTerminated = false;
                    }
                }

                // Clean up
                if (!completed) {
                    McpLogger.warn(LOG_TAG, "Test timed out - terminating processes...");
                    // Terminate if still running - try graceful first
                    for (IProcess process : launch.getProcesses()) {
                        if (!process.isTerminated()) {
                            McpLogger.debug(LOG_TAG, "Terminating process: " + process.getLabel());
                            process.terminate();
                        }
                    }
                }

                // Delete temporary launch config
                McpLogger.debug(LOG_TAG, "Deleting launch configuration...");
                config.delete();

                // Build result
                Map<String, Object> result = new HashMap<>();
                result.put("projectName", projectName);
                result.put("className", fullyQualifiedName);
                result.put("methodName", methodName);

                // Collect process output for diagnostics (especially useful for Spring Boot failures)
                StringBuilder processOutputBuilder = new StringBuilder();
                for (IProcess process : launch.getProcesses()) {
                    IStreamsProxy streamsProxy = process.getStreamsProxy();
                    if (streamsProxy != null) {
                        String stdout = streamsProxy.getOutputStreamMonitor().getContents();
                        String stderr = streamsProxy.getErrorStreamMonitor().getContents();
                        if (stdout != null && !stdout.isEmpty()) {
                            processOutputBuilder.append(stdout);
                        }
                        if (stderr != null && !stderr.isEmpty()) {
                            if (processOutputBuilder.length() > 0) {
                                processOutputBuilder.append("\n--- STDERR ---\n");
                            }
                            processOutputBuilder.append(stderr);
                        }
                    }
                }
                String processOutput = processOutputBuilder.toString();

                // Add diagnostic info
                Map<String, Object> diagnostics = new HashMap<>();
                diagnostics.put("sessionLaunched", sessionState[0]);
                diagnostics.put("sessionStarted", sessionState[1]);
                diagnostics.put("sessionFinished", sessionState[2]);
                diagnostics.put("processTerminated", processTerminated);
                if (sessionState[3] && errorMessage[0] != null) {
                    diagnostics.put("listenerError", errorMessage[0]);
                }
                result.put("diagnostics", diagnostics);

                if (integrationTestWarning != null) {
                    result.put("warning", integrationTestWarning);
                }

                if (!completed) {
                    result.put("status", "TIMEOUT");
                    String message = "Tests timed out after " + timeoutSeconds + " seconds.";
                    if (!sessionState[0]) {
                        message += " Test session never launched.";
                    } else if (!sessionState[1]) {
                        message += " Test session launched but never started - this may indicate a Spring context initialization issue.";
                    } else if (!sessionState[2]) {
                        message += " Test session started but never finished - tests may be hanging or waiting for external resources.";
                    }
                    McpLogger.warn(LOG_TAG, "Returning TIMEOUT result: " + message);
                    McpLogger.debug(LOG_TAG, "Session state: launched=" + sessionState[0] +
                            ", started=" + sessionState[1] + ", finished=" + sessionState[2] + ", hasError=" + sessionState[3]);
                    result.put("message", message);
                    result.put("testsRun", counts[0]);
                    result.put("testResults", testResults);
                    // Add truncated process output for debugging
                    if (!processOutput.isEmpty()) {
                        result.put("processOutput", truncateOutput(processOutput, 5000));
                    }
                    String jsonResult = MAPPER.writeValueAsString(result);
                    McpLogger.info(LOG_TAG, "=== Test run finished (TIMEOUT) ===");
                    return new CallToolResult(jsonResult, true);
                }

                // Check if session was properly initialized
                if (!sessionState[1]) {
                    result.put("status", "ERROR");
                    String message = "Test session was not properly initialized.";
                    if (sessionState[0]) {
                        message += " Session was launched but test tree was never created.";
                    } else {
                        message += " JUnit listener may not have received events.";
                    }
                    McpLogger.error(LOG_TAG, "Session not initialized: " + message);
                    result.put("message", message);
                    // Add process output to help diagnose the issue
                    if (!processOutput.isEmpty()) {
                        result.put("processOutput", truncateOutput(processOutput, 5000));
                    }
                    String jsonResult = MAPPER.writeValueAsString(result);
                    McpLogger.info(LOG_TAG, "=== Test run finished (ERROR - session not initialized) ===");
                    return new CallToolResult(jsonResult, true);
                }

                result.put("testsRun", counts[0]);
                result.put("passed", counts[1]);
                result.put("failures", counts[2]);
                result.put("errors", counts[3]);
                result.put("testResults", testResults);

                String status;
                if (counts[0] == 0) {
                    status = "WARNING";
                    result.put("status", status);
                    result.put("message", "No tests were executed. This may indicate that no @Test methods were found, " +
                        "or tests were filtered out. For integration tests, consider using Maven Failsafe.");
                } else if (counts[2] == 0 && counts[3] == 0) {
                    status = "SUCCESS";
                    result.put("status", status);
                    result.put("message", "All " + counts[0] + " tests passed");
                } else {
                    status = "FAILURE";
                    result.put("status", status);
                    result.put("message", counts[2] + " failures, " + counts[3] + " errors out of " + counts[0] + " tests");
                }

                String jsonResult = MAPPER.writeValueAsString(result);
                McpLogger.info(LOG_TAG, "=== Test run finished (" + status + ") ===");
                McpLogger.debug(LOG_TAG, "Result JSON length: " + jsonResult.length());
                return new CallToolResult(jsonResult, counts[2] > 0 || counts[3] > 0);

            } finally {
                // Stop heartbeat
                McpLogger.debug(LOG_TAG, "Stopping heartbeat executor...");
                heartbeatExecutor.shutdownNow();
                JUnitCore.removeTestRunListener(listener);
            }

        } catch (Exception e) {
            // Ensure heartbeat is stopped even on exception
            heartbeatExecutor.shutdownNow();
            return ToolErrors.errorResult("run tests", e);
        }
    }

    /**
     * Truncates a stack trace to keep responses manageable while preserving useful information.
     * Keeps: first line (error message), lines from test class, and first few framework lines.
     *
     * @param trace the full stack trace
     * @param testClassName the test class name to prioritize
     * @param maxLength maximum length of the result
     * @return truncated stack trace
     */
    private static String truncateStackTrace(String trace, String testClassName, int maxLength) {
        if (trace == null || trace.length() <= maxLength) {
            return trace;
        }

        String[] lines = trace.split("\n");
        StringBuilder result = new StringBuilder();
        int relevantLineCount = 0;
        boolean addedEllipsis = false;

        for (int i = 0; i < lines.length && result.length() < maxLength; i++) {
            String line = lines[i];
            boolean isRelevant = i == 0  // First line (error message)
                    || line.contains(testClassName)  // Lines from test class
                    || (i < 5 && line.trim().startsWith("at "))  // First few stack frames
                    || line.contains("Caused by:");  // Cause chain

            if (isRelevant) {
                if (addedEllipsis) {
                    addedEllipsis = false;
                }
                result.append(line).append("\n");
                relevantLineCount++;
            } else if (!addedEllipsis && relevantLineCount > 0) {
                result.append("\t... (truncated)\n");
                addedEllipsis = true;
            }
        }

        if (result.length() > maxLength) {
            return result.substring(0, maxLength - 20) + "\n... (truncated)";
        }

        return result.toString();
    }

    private static String truncateOutput(String output, int maxLength) {
        if (output == null || output.length() <= maxLength) {
            return output;
        }
        // For process output, keep the end (most recent/relevant) rather than the beginning
        int startIndex = output.length() - maxLength + 50;
        return "... (truncated) ...\n" + output.substring(startIndex);
    }

    /**
     * Detects if a test class is a Spring Boot integration test by checking for common annotations.
     */
    private static boolean detectIntegrationTest(IType testType) {
        try {
            // Check for Spring Boot test annotations
            String[] integrationAnnotations = {
                "SpringBootTest",
                "DataJpaTest",
                "WebMvcTest",
                "WebFluxTest",
                "JdbcTest",
                "DataMongoTest",
                "DataRedisTest",
                "RestClientTest",
                "AutoConfigureMockMvc",
                "Testcontainers",
                "Container"
            };

            // Check class annotations
            for (org.eclipse.jdt.core.IAnnotation annotation : testType.getAnnotations()) {
                String annotationName = annotation.getElementName();
                for (String integrationAnnotation : integrationAnnotations) {
                    if (annotationName.equals(integrationAnnotation) ||
                        annotationName.endsWith("." + integrationAnnotation)) {
                        return true;
                    }
                }
            }

            // Also check if class name ends with "IT" (common convention for integration tests)
            if (testType.getElementName().endsWith("IT")) {
                return true;
            }

            return false;
        } catch (Exception e) {
            // If we can't determine, assume it's not an integration test
            return false;
        }
    }

    /**
     * Finds the type (class) that contains a method with the given name.
     * First checks the given type, then recursively searches @Nested inner classes.
     *
     * @param type the type to search in
     * @param methodName the method name to find
     * @return the type containing the method, or null if not found
     */
    private static IType findTypeContainingMethod(IType type, String methodName) {
        try {
            // First check if the method exists in the main class
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(methodName)) {
                    return type;
                }
            }

            // Search in @Nested inner classes
            for (IType innerType : type.getTypes()) {
                if (hasNestedAnnotation(innerType)) {
                    IType found = findTypeContainingMethod(innerType, methodName);
                    if (found != null) {
                        return found;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if a type has the @Nested annotation (JUnit 5).
     */
    private static boolean hasNestedAnnotation(IType type) {
        try {
            for (org.eclipse.jdt.core.IAnnotation annotation : type.getAnnotations()) {
                String name = annotation.getElementName();
                if (name.equals("Nested") || name.equals("org.junit.jupiter.api.Nested")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    // ASYNC TEST TOOLS (Two-Tool Pattern for long-running tests)
    // ========================================================================

    /**
     * Creates the jdt_start_tests_async tool registration.
     * Starts tests in background and returns immediately with a taskId.
     */
    public static ToolRegistration startTestsAsyncTool() {
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> projectName = new HashMap<>();
        projectName.put("type", "string");
        projectName.put("description", "Eclipse project name (get from jdt_list_projects)");
        properties.put("projectName", projectName);

        Map<String, Object> className = new HashMap<>();
        className.put("type", "string");
        className.put("description", "Test class (simple name like 'UserServiceTest' or fully qualified)");
        properties.put("className", className);

        Map<String, Object> methodName = new HashMap<>();
        methodName.put("type", "string");
        methodName.put("description", "Run only this test method (optional)");
        properties.put("methodName", methodName);

        Map<String, Object> timeoutSeconds = new HashMap<>();
        timeoutSeconds.put("type", "integer");
        timeoutSeconds.put("description", "Max execution time in seconds (default: 300)");
        properties.put("timeoutSeconds", timeoutSeconds);

        JsonSchema schema = new JsonSchema("object", properties, List.of("projectName", "className"), null, null, null);

        Tool tool = new Tool(
                "jdt_start_tests_async",
                "🚀 START A LONG-RUNNING TEST! Use this instead of jdt_run_tests for a single integration test " +
                "(Spring Boot, Testcontainers) that takes >30s. Returns a taskId immediately. " +
                "⚠️ FOR ONE TEST AT A TIME - NOT for parallel execution of multiple tests! " +
                "Then call jdt_get_test_result(taskId) to check progress/results. " +
                "WORKFLOW: 1) jdt_start_tests_async → taskId  2) wait 30s  3) jdt_get_test_result(taskId) → repeat until done",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> startTestsAsync(
                (String) args.get("projectName"),
                (String) args.get("className"),
                (String) args.get("methodName"),
                args.get("timeoutSeconds") != null ? ((Number) args.get("timeoutSeconds")).intValue() : 300));
    }

    /**
     * Creates the jdt_get_test_result tool registration.
     * Gets the status/result of an async test run.
     */
    public static ToolRegistration getTestResultTool() {
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> taskId = new HashMap<>();
        taskId.put("type", "string");
        taskId.put("description", "The taskId returned by jdt_start_tests_async");
        properties.put("taskId", taskId);

        JsonSchema schema = new JsonSchema("object", properties, List.of("taskId"), null, null, null);

        Tool tool = new Tool(
                "jdt_get_test_result",
                "📊 Get status/result of async test run. Returns: status (STARTING/RUNNING/COMPLETED/ERROR/TIMEOUT), " +
                "progress (tests run/total), and results when completed. Call every 30s until status is COMPLETED.",
                schema,
                null);

        return new ToolRegistration(tool, (args, progress) -> getTestResult((String) args.get("taskId")));
    }

    private static CallToolResult startTestsAsync(String projectName, String className, String methodName, int timeoutSeconds) {
        final String LOG_TAG = "AsyncTests";
        String taskId = "test-" + System.currentTimeMillis();

        McpLogger.info(LOG_TAG, "Starting async test: " + taskId);
        McpLogger.info(LOG_TAG, "Project: " + projectName + ", Class: " + className +
                ", Method: " + (methodName != null ? methodName : "(all)") + ", Timeout: " + timeoutSeconds + "s");

        AsyncTestRegistry.TestSession session = new AsyncTestRegistry.TestSession(taskId, projectName, className, methodName);
        AsyncTestRegistry.getInstance().register(taskId, session);

        // Start test in background thread
        Thread testThread = new Thread(() -> {
            runTestsInBackground(session, timeoutSeconds);
        }, "AsyncTest-" + taskId);
        testThread.setDaemon(true);
        testThread.start();

        // Return immediately with taskId
        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("status", "STARTED");
        response.put("message", "Test started in background. Call jdt_get_test_result('" + taskId + "') to check progress.");

        try {
            return new CallToolResult(MAPPER.writeValueAsString(response), false);
        } catch (Exception e) {
            return new CallToolResult("{\"taskId\":\"" + taskId + "\",\"status\":\"STARTED\"}", false);
        }
    }

    private static void runTestsInBackground(AsyncTestRegistry.TestSession session, int timeoutSeconds) {
        final String LOG_TAG = "AsyncTests";

        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(session.getProjectName());
            if (project == null || !project.exists()) {
                session.setErrorMessage("Project not found: " + session.getProjectName());
                session.complete(AsyncTestRegistry.TestSession.Status.ERROR);
                return;
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                session.setErrorMessage("Not a Java project: " + session.getProjectName());
                session.complete(AsyncTestRegistry.TestSession.Status.ERROR);
                return;
            }

            // Find test class
            String className = session.getClassName();
            IType testType = javaProject.findType(className);

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
                session.setErrorMessage("Test class not found: " + className);
                session.complete(AsyncTestRegistry.TestSession.Status.ERROR);
                return;
            }

            final IType finalTestType = testType;
            session.setStatus(AsyncTestRegistry.TestSession.Status.RUNNING);

            // Count expected tests
            List<String> expectedTests = new ArrayList<>();
            collectTestMethods(finalTestType, expectedTests);
            session.setExpectedTotal(session.getMethodName() != null ? 1 : expectedTests.size());

            // Create launch configuration
            String configName = "MCP-AsyncTest-" + session.getTaskId();
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig");
            ILaunchConfigurationWorkingCopy workingCopy = type.newInstance(null, configName);

            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, session.getProjectName());
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, finalTestType.getFullyQualifiedName());
            workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");

            if (session.getMethodName() != null) {
                workingCopy.setAttribute("org.eclipse.jdt.junit.TESTNAME", session.getMethodName());
            }

            ILaunchConfiguration config = workingCopy.doSave();
            final CountDownLatch latch = new CountDownLatch(1);

            TestRunListener listener = new TestRunListener() {
                @Override
                public void sessionLaunched(ITestRunSession testSession) {
                    if (!testSession.getTestRunName().equals(configName)) return;
                    McpLogger.debug(LOG_TAG, "Session launched: " + session.getTaskId());
                }

                @Override
                public void sessionStarted(ITestRunSession testSession) {
                    if (!testSession.getTestRunName().equals(configName)) return;
                    McpLogger.debug(LOG_TAG, "Session started: " + session.getTaskId());
                }

                @Override
                public void sessionFinished(ITestRunSession testSession) {
                    if (!testSession.getTestRunName().equals(configName)) return;
                    McpLogger.info(LOG_TAG, "Session finished: " + session.getTaskId() +
                            " - Passed: " + session.getTestsPassed() + ", Failed: " + session.getTestsFailed());

                    // Build final result JSON
                    try {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", session.getTestsFailed() > 0 || session.getTestsError() > 0 ? "FAILED" : "SUCCESS");
                        result.put("projectName", session.getProjectName());
                        result.put("className", session.getClassName());
                        result.put("testsRun", session.getTestsRun());
                        result.put("passed", session.getTestsPassed());
                        result.put("failures", session.getTestsFailed());
                        result.put("errors", session.getTestsError());
                        result.put("testResults", session.getTestResults());
                        result.put("durationSeconds", session.getElapsedSeconds());
                        session.setResultJson(MAPPER.writeValueAsString(result));
                    } catch (Exception e) {
                        McpLogger.error(LOG_TAG, "Error building result JSON", e);
                    }

                    session.complete(AsyncTestRegistry.TestSession.Status.COMPLETED);
                    latch.countDown();
                }

                @Override
                public void testCaseFinished(ITestCaseElement testCaseElement) {
                    if (!testCaseElement.getTestRunSession().getTestRunName().equals(configName)) return;

                    session.incrementTestsRun();
                    ITestElement.Result result = testCaseElement.getTestResult(false);

                    if (result == ITestElement.Result.OK) {
                        session.incrementTestsPassed();
                    } else if (result == ITestElement.Result.FAILURE) {
                        session.incrementTestsFailed();
                    } else if (result == ITestElement.Result.ERROR) {
                        session.incrementTestsError();
                    }

                    session.setCurrentTest(testCaseElement.getTestMethodName());

                    // Store test result details
                    Map<String, Object> testResult = new HashMap<>();
                    testResult.put("className", testCaseElement.getTestClassName());
                    testResult.put("methodName", testCaseElement.getTestMethodName());
                    testResult.put("status", result.toString());
                    testResult.put("duration", testCaseElement.getElapsedTimeInSeconds());

                    if (result == ITestElement.Result.FAILURE || result == ITestElement.Result.ERROR) {
                        String trace = testCaseElement.getFailureTrace() != null
                                ? testCaseElement.getFailureTrace().getTrace() : null;
                        if (trace != null) {
                            testResult.put("failureTrace", truncateStackTrace(trace, session.getClassName(), 2000));
                        }
                    }
                    session.addTestResult(testResult);

                    McpLogger.debug(LOG_TAG, "Test finished: " + testCaseElement.getTestMethodName() + " = " + result);
                }
            };

            JUnitCore.addTestRunListener(listener);

            try {
                ILaunch launch = config.launch(ILaunchManager.RUN_MODE, null, true);

                // Wait for completion with timeout
                boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);

                if (!completed) {
                    McpLogger.warn(LOG_TAG, "Test timeout: " + session.getTaskId());
                    session.setErrorMessage("Test execution timed out after " + timeoutSeconds + "s");
                    session.complete(AsyncTestRegistry.TestSession.Status.TIMEOUT);

                    // Try to terminate the launch
                    if (launch != null && !launch.isTerminated()) {
                        launch.terminate();
                    }
                }
            } finally {
                JUnitCore.removeTestRunListener(listener);
                // Clean up launch config
                try {
                    config.delete();
                } catch (Exception e) {
                    McpLogger.warn(LOG_TAG, "Failed to delete launch config: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            McpLogger.error(LOG_TAG, "Error running async test", e);
            session.setErrorMessage("Error: " + e.getMessage());
            session.complete(AsyncTestRegistry.TestSession.Status.ERROR);
        }
    }

    private static CallToolResult getTestResult(String taskId) {
        final String LOG_TAG = "AsyncTests";

        AsyncTestRegistry.TestSession session = AsyncTestRegistry.getInstance().get(taskId);
        if (session == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "NOT_FOUND");
            error.put("message", "No test session found with taskId: " + taskId);
            try {
                return new CallToolResult(MAPPER.writeValueAsString(error), true);
            } catch (Exception e) {
                return new CallToolResult("{\"status\":\"NOT_FOUND\"}", true);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("status", session.getStatus().name());
        response.put("elapsedSeconds", session.getElapsedSeconds());
        response.put("testsRun", session.getTestsRun());
        response.put("expectedTotal", session.getExpectedTotal());
        response.put("passed", session.getTestsPassed());
        response.put("failed", session.getTestsFailed());
        response.put("errors", session.getTestsError());

        if (session.getCurrentTest() != null) {
            response.put("currentTest", session.getCurrentTest());
        }

        if (session.isCompleted()) {
            if (session.getErrorMessage() != null) {
                response.put("errorMessage", session.getErrorMessage());
            }
            if (session.getResultJson() != null) {
                response.put("results", session.getTestResults());
            }
            // Clean up after retrieval (keep for 10 min in case of re-fetch)
            AsyncTestRegistry.getInstance().cleanupOldSessions();
        } else {
            response.put("message", "Test still running. Call again in 30s.");
        }

        try {
            McpLogger.debug(LOG_TAG, "getTestResult(" + taskId + "): " + session.getStatus() +
                    " - " + session.getTestsRun() + "/" + session.getExpectedTotal());
            return new CallToolResult(MAPPER.writeValueAsString(response), false);
        } catch (Exception e) {
            return new CallToolResult("{\"status\":\"" + session.getStatus() + "\"}", false);
        }
    }
}
