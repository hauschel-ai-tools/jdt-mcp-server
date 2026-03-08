package org.fixture.core;

import org.fixture.api.Auditable;
import org.fixture.api.Configurable;
import org.fixture.api.Priority;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple processor that handles String items.
 */
@Auditable(value = "processor", enabled = true)
public class SimpleProcessor extends BaseProcessor<String> implements Configurable {

    private Priority priority;
    private final Map<String, String> config = new HashMap<>();
    public String publicField = "exposed";

    public SimpleProcessor() {
        super("simple");
        this.priority = Priority.MEDIUM;
    }

    @Override
    public String process(String item) {
        incrementCount();
        return "Processed: " + item;
    }

    @Override
    public boolean canHandle(String item) {
        return item != null && !item.isEmpty();
    }

    @Override
    public void configure(String key, String value) {
        config.put(key, value);
    }

    @Override
    public String getConfig(String key) {
        return config.get(key);
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    /**
     * Formats a result string with prefix and suffix.
     * This method is a candidate for extractMethod refactoring.
     */
    public String formatResult(String input) {
        String prefix = "[" + getName() + "]";
        String suffix = "(priority=" + priority + ")";
        String result = prefix + " " + input + " " + suffix;
        return result;
    }

    /**
     * A helper that wraps process() -- candidate for inline refactoring.
     */
    public String processAndFormat(String item) {
        String processed = process(item);
        return formatResult(processed);
    }
}
