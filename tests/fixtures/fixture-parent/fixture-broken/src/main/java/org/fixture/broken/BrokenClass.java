package org.fixture.broken;

// Missing import for List -- compilation error
public class BrokenClass {

    // Type error: assigning String to int
    private int count = "not a number";

    // Unknown type -- compilation error
    private NonExistentType field;

    public void method() {
        // Method body references unknown type
        List<String> items = new ArrayList<>();
    }
}
