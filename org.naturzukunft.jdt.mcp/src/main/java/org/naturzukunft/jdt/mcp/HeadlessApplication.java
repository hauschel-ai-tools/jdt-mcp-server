package org.naturzukunft.jdt.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import org.naturzukunft.jdt.mcp.server.McpStdioServer;

/**
 * Headless Eclipse application for running the JDT MCP server standalone.
 * Starts the MCP server immediately, then imports projects in the background.
 */
public class HeadlessApplication implements IApplication {

    private static final AtomicBoolean importing = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public static boolean isImporting() {
        return importing.get();
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

        importing.set(true);
        Thread importThread = new Thread(() -> {
            try {
                List<IProject> projects = ProjectImporter.importFromDirectory(
                        Path.of(workDir), new NullProgressMonitor());

                McpLogger.info("HeadlessApplication", "Imported " + projects.size() + " project(s):");
                for (IProject project : projects) {
                    McpLogger.info("HeadlessApplication", "  - " + project.getName() +
                            " (" + project.getLocation() + ")");
                }
            } catch (Exception e) {
                McpLogger.error("HeadlessApplication", "Project import failed", e);
            } finally {
                importing.set(false);
                McpLogger.info("HeadlessApplication", "Project import finished");
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
