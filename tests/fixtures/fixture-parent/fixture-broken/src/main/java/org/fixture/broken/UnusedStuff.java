package org.fixture.broken;

import java.util.List;              // unused import
import java.util.Map;               // unused import
import org.fixture.api.Processor;   // unused import

public class UnusedStuff {

    private int unusedField1 = 42;
    private String unusedField2 = "never read";

    public void usedMethod() {
        System.out.println("this is used");
    }

    private void unusedMethod1() {
        System.out.println("never called 1");
    }

    private void unusedMethod2() {
        System.out.println("never called 2");
    }
}
