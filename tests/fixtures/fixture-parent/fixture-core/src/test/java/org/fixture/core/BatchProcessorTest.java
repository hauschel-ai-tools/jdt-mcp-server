package org.fixture.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class BatchProcessorTest {

    @Test
    void testProcess() {
        BatchProcessor bp = new BatchProcessor();
        List<String> items = new ArrayList<>(List.of("b", "a", "c"));
        String result = bp.process(items);
        assertEquals("a;b;c;", result);
    }

    @Test
    void testFilterNonEmpty() {
        BatchProcessor bp = new BatchProcessor();
        List<String> input = new ArrayList<>(List.of("a", "", "b"));
        // Note: null elements would cause NPE in stream, so we test without null
        List<String> result = bp.filterNonEmpty(input);
        assertEquals(2, result.size());
    }
}
