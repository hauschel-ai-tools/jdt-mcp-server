package org.naturzukunft.jdt.mcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Simple file-based logger for JDT MCP Server debugging.
 * Logs to:
 * - ~/.jdt-mcp/jdt-mcp.log (always)
 * - Eclipse Error Log (for WARN and ERROR levels)
 */
public class McpLogger {

    private static final String LOG_DIR = System.getProperty("user.home") + File.separator + ".jdt-mcp";
    private static final String LOG_FILE = LOG_DIR + File.separator + "jdt-mcp.log";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10 MB

    private static boolean initialized = false;
    private static boolean enabled = true;

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Initialize the logger (creates directory if needed, rotates old log).
     */
    public static synchronized void init() {
        if (initialized) return;

        try {
            File logDir = new File(LOG_DIR);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            // Rotate log if too large
            File logFile = new File(LOG_FILE);
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                File backup = new File(LOG_FILE + ".old");
                if (backup.exists()) {
                    backup.delete();
                }
                logFile.renameTo(backup);
            }

            initialized = true;
            info("McpLogger", "=== JDT MCP Logger initialized ===");
            info("McpLogger", "Log file: " + LOG_FILE);

        } catch (Exception e) {
            System.err.println("[JDT MCP] Failed to initialize logger: " + e.getMessage());
            enabled = false;
        }
    }

    /**
     * Log a DEBUG message.
     */
    public static void debug(String component, String message) {
        log(Level.DEBUG, component, message);
    }

    /**
     * Log an INFO message.
     */
    public static void info(String component, String message) {
        log(Level.INFO, component, message);
    }

    /**
     * Log a WARN message.
     */
    public static void warn(String component, String message) {
        log(Level.WARN, component, message);
    }

    /**
     * Log an ERROR message.
     */
    public static void error(String component, String message) {
        log(Level.ERROR, component, message);
    }

    /**
     * Log an ERROR message with exception.
     */
    public static void error(String component, String message, Throwable t) {
        log(Level.ERROR, component, message, t);
    }

    /**
     * Log a message with the specified level.
     */
    public static void log(Level level, String component, String message) {
        log(level, component, message, null);
    }

    /**
     * Log a message with the specified level and optional exception.
     */
    public static void log(Level level, String component, String message, Throwable t) {
        if (!enabled) return;
        if (!initialized) init();

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String threadName = Thread.currentThread().getName();
        String logLine = String.format("[%s] [%s] [%s] [%s] %s%n",
                timestamp, level, threadName, component, message);

        // Also print to console for immediate visibility
        System.out.print("[JDT MCP] " + logLine);

        // Write to file
        synchronized (McpLogger.class) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                writer.write(logLine);
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    writer.write(sw.toString());
                    writer.write("\n");
                }
            } catch (IOException e) {
                System.err.println("[JDT MCP] Failed to write log: " + e.getMessage());
            }
        }

        // Also log to Eclipse Error Log for WARN and ERROR
        if (level == Level.WARN || level == Level.ERROR) {
            logToEclipse(level, component, message, t);
        }
    }

    /**
     * Log to Eclipse Error Log.
     */
    private static void logToEclipse(Level level, String component, String message, Throwable t) {
        try {
            ILog log = Platform.getLog(McpLogger.class);
            int severity = level == Level.ERROR ? IStatus.ERROR : IStatus.WARNING;
            String fullMessage = "[" + component + "] " + message;
            IStatus status = new Status(severity, Activator.PLUGIN_ID, fullMessage, t);
            log.log(status);
        } catch (Exception e) {
            // Ignore - Eclipse may not be fully initialized
        }
    }

    /**
     * Get the log file path.
     */
    public static String getLogFilePath() {
        return LOG_FILE;
    }

    /**
     * Enable or disable logging.
     */
    public static void setEnabled(boolean enable) {
        enabled = enable;
    }
}
