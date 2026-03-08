package org.fixture.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

class AppServiceTest {

    @Test
    void testRun() {
        AppService svc = new AppService();
        String result = svc.run("  Hello World  ");
        assertNotNull(result);
        assertTrue(result.contains("hello world"));
    }

    @Nested
    class PriorityTests {
        @Test
        void testRunWithPriority() {
            AppService svc = new AppService();
            String result = svc.runWithPriority("test", 5);
            assertTrue(result.contains("[p=5]"));
        }
    }
}
