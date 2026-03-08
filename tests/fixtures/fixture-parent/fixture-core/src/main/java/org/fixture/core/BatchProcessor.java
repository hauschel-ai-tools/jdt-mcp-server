package org.fixture.core;

import org.fixture.api.Tracked;
import java.util.List;
import java.util.Comparator;

@Tracked
public class BatchProcessor extends BaseProcessor<List<String>> {

    @SuppressWarnings("unused")
    private int unusedCounter = 0;

    public BatchProcessor() {
        super("batch");
    }

    @Override
    public String process(List<String> items) {
        incrementCount();
        StringBuilder sb = new StringBuilder();
        // Anonymous class -- candidate for convertToLambda
        items.sort(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        });
        for (String item : items) {
            sb.append(item).append(";");
        }
        return sb.toString();
    }

    @Override
    public boolean canHandle(List<String> items) {
        return items != null && !items.isEmpty();
    }

    /**
     * Uses method reference -- for testing rename with ::
     */
    public List<String> filterNonEmpty(List<String> items) {
        return items.stream()
                .filter(this::isNonEmpty)
                .toList();
    }

    private boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // Unused private method -- for findUnusedCode
    private void unusedPrivateMethod() {
        System.out.println("never called");
    }
}
