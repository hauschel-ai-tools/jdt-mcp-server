package org.naturzukunft.jdt.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import org.naturzukunft.jdt.mcp.server.McpStdioServer;

/**
 * Headless Eclipse application for running the JDT MCP server standalone.
 * Starts the MCP server immediately, then imports projects and builds in the background.
 * Tools are blocked via {@link #awaitReady(long, TimeUnit)} until import and build are complete.
 */
public class HeadlessApplication implements IApplication {

    private static volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * Returns true if project import and build are still in progress.
     */
    public static boolean isImporting() {
        return readyLatch.getCount() > 0;
    }

    /**
     * Blocks until project import and build are complete, or the timeout expires.
     *
     * @return true if ready, false if timeout elapsed
     */
    public static boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    /**
     * Reloads the workspace: removes all projects, re-imports from working directory, and rebuilds.
     * Other tools are blocked via {@link #awaitReady(long, TimeUnit)} while reload is in progress.
     *
     * @return list of imported projects
     */
    public static List<IProject> reloadWorkspace() throws Exception {
        McpLogger.info("HeadlessApplication", "Reload workspace requested");

        // Block other tools during reload
        readyLatch = new CountDownLatch(1);

        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();

            // Remove all projects from workspace (keep files on disk)
            IProject[] existing = workspace.getRoot().getProjects();
            McpLogger.info("HeadlessApplication", "Removing " + existing.length + " project(s) from workspace");
            for (IProject project : existing) {
                project.delete(false, true, new NullProgressMonitor());
            }

            // Re-import from working directory
            String workDir = System.getProperty("user.dir");
            List<IProject> projects = ProjectImporter.importFromDirectory(
                    Path.of(workDir), new NullProgressMonitor());

            McpLogger.info("HeadlessApplication", "Re-imported " + projects.size() + " project(s):");
            for (IProject project : projects) {
                McpLogger.info("HeadlessApplication", "  - " + project.getName() +
                        " (" + project.getLocation() + ")");
            }

            // Rebuild
            workspace.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
            McpLogger.info("HeadlessApplication", "Workspace rebuild completed");

            return projects;
        } finally {
            readyLatch.countDown();
            McpLogger.info("HeadlessApplication", "Reload workspace finished — ready for requests");
        }
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Signal that the application is running (prevents splash screen wait)
        context.applicationRunning();

        McpLogger.init();
        McpLogger.info("HeadlessApplication", "Starting JDT MCP Server (standalone)");

        // Start MCP server FIRST so clients can connect immediately
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.startMcpServer();
        }

        // Import projects from working directory in background
        String workDir = System.getProperty("user.dir");
        McpLogger.info("HeadlessApplication", "Working directory: " + workDir);

        Thread importThread = new Thread(() -> {
            try {
                List<IProject> projects = ProjectImporter.importFromDirectory(
                        Path.of(workDir), new NullProgressMonitor());

                McpLogger.info("HeadlessApplication", "Imported " + projects.size() + " project(s):");
                for (IProject project : projects) {
                    McpLogger.info("HeadlessApplication", "  - " + project.getName() +
                            " (" + project.getLocation() + ")");
                }

                // Enable auto-building and trigger explicit build (#27)
                IWorkspace workspace = ResourcesPlugin.getWorkspace();
                var desc = workspace.getDescription();
                desc.setAutoBuilding(true);
                workspace.setDescription(desc);
                McpLogger.info("HeadlessApplication", "Auto-building enabled, triggering workspace build...");

                workspace.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor());
                McpLogger.info("HeadlessApplication", "Workspace build completed");

            } catch (Exception e) {
                McpLogger.error("HeadlessApplication", "Project import/build failed", e);
            } finally {
                readyLatch.countDown();
                McpLogger.info("HeadlessApplication", "Project import and build finished — ready for requests");
            }
        }, "jdtmcp-project-importer");
        importThread.setDaemon(true);
        importThread.start();

        McpServerManager manager = activator != null ? activator.getMcpServerManager() : null;
        if (manager != null && manager.isRunning()) {
            String transport = manager.getTransport();
            if ("stdio".equals(transport)) {
                McpLogger.info("HeadlessApplication", "MCP server running on stdio");

                // In stdio mode, wait for stdin to close (client disconnects)
                McpStdioServer stdioServer = manager.getStdioServer();
                if (stdioServer != null) {
                    stdioServer.awaitStop();
                }
            } else {
                McpLogger.info("HeadlessApplication",
                        "MCP server running at " + manager.getSseEndpoint());

                // In HTTP mode, block until shutdown signal
                shutdownLatch.await();
            }
        } else {
            McpLogger.error("HeadlessApplication", "MCP server failed to start");
        }

        McpLogger.info("HeadlessApplication", "Shutting down");
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        McpLogger.info("HeadlessApplication", "Stop requested");
        shutdownLatch.countDown();
    }
}
