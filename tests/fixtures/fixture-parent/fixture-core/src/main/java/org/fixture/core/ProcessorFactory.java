package org.fixture.core;

import org.fixture.api.Processor;
import org.fixture.api.Priority;

public class ProcessorFactory {

    /**
     * Creates a SimpleProcessor with default config.
     * Multiple callers reference this -- good for findCallers test.
     */
    public static SimpleProcessor createDefault() {
        SimpleProcessor sp = new SimpleProcessor();
        sp.setPriority(Priority.MEDIUM);
        return sp;
    }

    /**
     * Creates a processor based on type name.
     */
    public static Processor<?> create(String type) {
        if ("simple".equals(type)) {
            return createDefault();
        } else if ("batch".equals(type)) {
            return new BatchProcessor();
        }
        return createDefault();
    }

    /**
     * An inlineable method: single expression, used once.
     * Candidate for jdt_inline.
     */
    public static String getDefaultName() {
        return "default-processor";
    }

    /**
     * Uses getDefaultName() -- the caller for inline test.
     */
    public static SimpleProcessor createNamed() {
        String name = getDefaultName();
        SimpleProcessor sp = new SimpleProcessor();
        sp.configure("name", name);
        return sp;
    }
}
