package org.fixture.external;

import org.fixture.api.Processor;
import org.fixture.api.Priority;
import org.fixture.api.Tracked;
import org.fixture.core.ProcessorFactory;
import org.fixture.core.SimpleProcessor;
import org.fixture.core.HelperUtil;

/**
 * Service in a separate project (not a module of fixture-parent).
 * Tests cross-project refactoring between independently imported projects.
 *
 * <p>References types from fixture-api AND fixture-core.</p>
 */
@Tracked
public class ExternalService {

    private final Processor<String> processor;
    private Priority priority;

    public ExternalService() {
        this.processor = ProcessorFactory.createDefault();
        this.priority = Priority.HIGH;
    }

    public String execute(String input) {
        String sanitized = HelperUtil.sanitize(input);
        return processor.process(sanitized);
    }

    /**
     * Uses method reference from external project -- for cross-project :: rename test.
     */
    public String executeAndFormat(String input) {
        SimpleProcessor sp = ProcessorFactory.createDefault();
        return sp.processAndFormat(input);
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }
}
