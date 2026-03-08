package org.fixture.core;

import org.fixture.api.Auditable;

/**
 * A data class with public fields -- for encapsulateField, generateGettersSetters,
 * generateConstructor, generateEqualsHashCode, generateToString.
 */
@Auditable("data")
public class DataHolder {

    public String id;
    public String name;
    public int count;
    public boolean active;

}
