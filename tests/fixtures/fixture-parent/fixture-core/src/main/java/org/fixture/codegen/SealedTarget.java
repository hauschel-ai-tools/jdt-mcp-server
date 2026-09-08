package org.fixture.codegen;

/**
 * A sealed class: the permits clause follows the implements list, so appending at the end of
 * the header would extend permits instead of implements.
 */
public sealed class SealedTarget implements Transformer<String> permits SquareTarget {

    @Override
    public String transform(String input) {
        return input;
    }
}
