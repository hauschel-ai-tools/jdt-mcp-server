package org.fixture.app;

import org.fixture.api.Processor;
import org.fixture.api.Tracked;
import org.fixture.core.SimpleProcessor;
import org.fixture.core.ProcessorFactory;
import org.fixture.core.HelperUtil;

/**
 * Application service that uses the processing API.
 * Cross-module references for testing findReferences, findCallers.
 */
@Tracked
public class AppService {

    private final Processor<String> processor;

    public AppService() {
        this.processor = ProcessorFactory.createDefault();
    }

    public String run(String input) {
        String sanitized = HelperUtil.sanitize(input);
        return processor.process(sanitized);
    }

    /**
     * Method without documented parameters -- for generateJavadoc.
     */
    public String runWithPriority(String input, int priority) {
        String sanitized = HelperUtil.sanitize(input);
        return processor.process(sanitized) + " [p=" + priority + "]";
    }
}
