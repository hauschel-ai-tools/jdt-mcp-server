package org.fixture.core;

import org.fixture.api.Processor;
import org.fixture.api.Tracked;

/**
 * Base implementation of Processor with common logic.
 */
@Tracked
public abstract class BaseProcessor<T> implements Processor<T> {

    private String name;
    private int processedCount;

    public BaseProcessor(String name) {
        this.name = name;
        this.processedCount = 0;
    }

    public String getName() {
        return name;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    protected void incrementCount() {
        processedCount++;
    }
}
