package org.naturzukunft.jdt.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing async test runs.
 * Supports the two-tool pattern to work around Claude Code's 60s MCP timeout.
 */
public class AsyncTestRegistry {

    private static final AsyncTestRegistry INSTANCE = new AsyncTestRegistry();

    private final Map<String, TestSession> sessions = new ConcurrentHashMap<>();

    private AsyncTestRegistry() {}

    public static AsyncTestRegistry getInstance() {
        return INSTANCE;
    }

    public void register(String taskId, TestSession session) {
        sessions.put(taskId, session);
    }

    public TestSession get(String taskId) {
        return sessions.get(taskId);
    }

    public void remove(String taskId) {
        sessions.remove(taskId);
    }

    /**
     * Clean up old completed sessions (older than 10 minutes).
     */
    public void cleanupOldSessions() {
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000);
        sessions.entrySet().removeIf(entry -> {
            TestSession session = entry.getValue();
            return session.isCompleted() && session.getCompletedAt() < cutoff;
        });
    }

    /**
     * Represents an async test session.
     */
    public static class TestSession {

        public enum Status {
            STARTING, RUNNING, COMPLETED, ERROR, TIMEOUT
        }

        private final String taskId;
        private final String projectName;
        private final String className;
        private final String methodName;
        private final long startedAt;

        private volatile Status status = Status.STARTING;
        private volatile long completedAt;
        private volatile String errorMessage;

        // Progress tracking
        private volatile int testsRun = 0;
        private volatile int testsPassed = 0;
        private volatile int testsFailed = 0;
        private volatile int testsError = 0;
        private volatile int expectedTotal = 0;
        private volatile String currentTest;

        // Final results
        private volatile List<Map<String, Object>> testResults = new ArrayList<>();
        private volatile String resultJson;

        public TestSession(String taskId, String projectName, String className, String methodName) {
            this.taskId = taskId;
            this.projectName = projectName;
            this.className = className;
            this.methodName = methodName;
            this.startedAt = System.currentTimeMillis();
        }

        // Getters
        public String getTaskId() { return taskId; }
        public String getProjectName() { return projectName; }
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public long getStartedAt() { return startedAt; }
        public Status getStatus() { return status; }
        public long getCompletedAt() { return completedAt; }
        public String getErrorMessage() { return errorMessage; }
        public int getTestsRun() { return testsRun; }
        public int getTestsPassed() { return testsPassed; }
        public int getTestsFailed() { return testsFailed; }
        public int getTestsError() { return testsError; }
        public int getExpectedTotal() { return expectedTotal; }
        public String getCurrentTest() { return currentTest; }
        public List<Map<String, Object>> getTestResults() { return testResults; }
        public String getResultJson() { return resultJson; }

        // Setters for updates
        public void setStatus(Status status) { this.status = status; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public void setExpectedTotal(int expectedTotal) { this.expectedTotal = expectedTotal; }
        public void setCurrentTest(String currentTest) { this.currentTest = currentTest; }
        public void setResultJson(String resultJson) { this.resultJson = resultJson; }

        public void incrementTestsRun() { this.testsRun++; }
        public void incrementTestsPassed() { this.testsPassed++; }
        public void incrementTestsFailed() { this.testsFailed++; }
        public void incrementTestsError() { this.testsError++; }

        public void addTestResult(Map<String, Object> result) {
            this.testResults.add(result);
        }

        public void complete(Status status) {
            this.status = status;
            this.completedAt = System.currentTimeMillis();
        }

        public boolean isCompleted() {
            return status == Status.COMPLETED || status == Status.ERROR || status == Status.TIMEOUT;
        }

        public long getElapsedSeconds() {
            long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
            return (end - startedAt) / 1000;
        }
    }
}
