package org.fixture.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

class SimpleProcessorTest {

    @Test
    void testProcess() {
        SimpleProcessor sp = new SimpleProcessor();
        assertEquals("Processed: hello", sp.process("hello"));
    }

    @Test
    void testCanHandle() {
        SimpleProcessor sp = new SimpleProcessor();
        assertTrue(sp.canHandle("test"));
        assertFalse(sp.canHandle(""));
        assertFalse(sp.canHandle(null));
    }

    @Nested
    class ConfigTests {
        @Test
        void testConfigure() {
            SimpleProcessor sp = new SimpleProcessor();
            sp.configure("key1", "value1");
            assertEquals("value1", sp.getConfig("key1"));
        }
    }
}
