package org.fixture.codegen;

/**
 * Interface used by the codegen fixtures to build non-trivial implements clauses.
 *
 * @param <T> the transformed type
 */
public interface Transformer<T> {
    T transform(T input);
}
