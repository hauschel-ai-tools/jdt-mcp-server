package org.fixture.api;

/**
 * Processes items of a given type.
 *
 * @param <T> the type of items to process
 */
public interface Processor<T> {
    /**
     * Process a single item.
     * @param item the item to process
     * @return the processing result as a string
     */
    String process(T item);

    /**
     * Check if this processor can handle the given item.
     * @param item the item to check
     * @return true if can handle
     */
    boolean canHandle(T item);
}
