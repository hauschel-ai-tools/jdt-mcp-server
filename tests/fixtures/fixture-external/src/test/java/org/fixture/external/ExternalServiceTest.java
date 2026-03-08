package org.fixture.external;

import org.fixture.api.Priority;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalServiceTest {

    @Test
    void testExecute() {
        ExternalService svc = new ExternalService();
        String result = svc.execute("  Test Input  ");
        assertNotNull(result);
        assertTrue(result.contains("test input"));
    }

    @Test
    void testPriority() {
        ExternalService svc = new ExternalService();
        assertEquals(Priority.HIGH, svc.getPriority());
    }

    @Test
    void testExecuteAndFormat() {
        ExternalService svc = new ExternalService();
        String result = svc.executeAndFormat("hello");
        assertNotNull(result);
        assertTrue(result.contains("hello"));
    }
}
