package org.naturzukunft.jdt.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import org.naturzukunft.jdt.mcp.server.McpStdioServer;

/**
 * Headless Eclipse application for running the JDT MCP server standalone.
 * Imports the current working directory as a Java project and keeps the server running.
 */
public class HeadlessApplication implements IApplication {

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Signal that the application is running (prevents splash screen wait)
        context.applicationRunning();

        McpLogger.init();
        McpLogger.info("HeadlessApplication", "Starting JDT MCP Server (standalone)");

        // Import project from working directory
        String workDir = System.getProperty("user.dir");
        McpLogger.info("HeadlessApplication", "Working directory: " + workDir);

        List<IProject> projects = ProjectImporter.importFromDirectory(
                Path.of(workDir), new NullProgressMonitor());

        McpLogger.info("HeadlessApplication", "Imported " + projects.size() + " project(s):");
        for (IProject project : projects) {
            McpLogger.info("HeadlessApplication", "  - " + project.getName() +
                    " (" + project.getLocation() + ")");
        }

        // Start MCP server (Activator skips auto-start in headless mode)
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.startMcpServer();
        }

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
