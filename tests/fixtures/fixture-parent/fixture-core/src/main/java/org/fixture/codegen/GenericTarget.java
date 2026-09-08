package org.fixture.codegen;

import java.util.List;
import java.util.Map;

/**
 * extends clause plus an existing implements list whose only entry has nested generics.
 */
public class GenericTarget extends BaseHolder implements Transformer<Map<String, List<Integer>>> {

    @Override
    public Map<String, List<Integer>> transform(Map<String, List<Integer>> input) {
        return input;
    }
}
