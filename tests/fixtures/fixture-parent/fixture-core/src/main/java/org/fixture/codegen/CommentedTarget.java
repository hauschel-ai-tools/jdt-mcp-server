package org.fixture.codegen;

/**
 * A comment inside the declaration header that contains an opening brace, ahead of the real
 * body brace and ahead of the implements clause.
 */
public class CommentedTarget extends BaseHolder /* e.g. Map<String, List<Integer>> { */ implements Transformer<String> {

    @Override
    public String transform(String input) {
        return input;
    }
}
