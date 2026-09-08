package org.fixture.codegen;

import org.fixture.api.Auditable;

/**
 * Several annotations (one of them with an array member, i.e. braces before the type name)
 * and a bounded type parameter that itself contains the keyword extends.
 */
@Auditable("codegen")
@SuppressWarnings({ "unchecked", "rawtypes" })
public class AnnotatedTarget<T extends Comparable<? super T>> {
    private T current;

    public T current() {
        return current;
    }
}
