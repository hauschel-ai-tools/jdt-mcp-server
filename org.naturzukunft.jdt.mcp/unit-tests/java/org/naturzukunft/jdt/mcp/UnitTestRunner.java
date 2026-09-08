package org.naturzukunft.jdt.mcp;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import java.io.PrintWriter;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Standalone JUnit 5 runner for this module's plain-Java unit tests (no Eclipse/OSGi
 * dependency), invoked by {@code tests/run-unit-tests.sh}. {@code org.naturzukunft.jdt.mcp} is
 * a Tycho {@code eclipse-plugin} module with no Tycho-surefire test fragment set up, so this
 * bypasses the Maven reactor entirely -- see #82.
 */
public final class UnitTestRunner {

    private UnitTestRunner() {
    }

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("org.naturzukunft.jdt.mcp"))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out), 100);

        if (summary.getTotalFailureCount() > 0 || summary.getTestsFoundCount() == 0) {
            System.exit(1);
        }
    }
}
