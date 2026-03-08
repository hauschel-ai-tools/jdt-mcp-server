package org.fixture.app;

import org.fixture.core.ProcessorFactory;
import org.fixture.core.SimpleProcessor;

public class Main {

    public static void main(String[] args) {
        SimpleProcessor sp = ProcessorFactory.createDefault();
        String result = sp.process("Hello from Main");
        System.out.println(result);
        System.out.println("Args: " + java.util.Arrays.toString(args));
    }
}
