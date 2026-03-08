package org.fixture.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessorTest {
    @Test
    void priorityValues() {
        assertEquals(4, Priority.values().length);
    }
}
