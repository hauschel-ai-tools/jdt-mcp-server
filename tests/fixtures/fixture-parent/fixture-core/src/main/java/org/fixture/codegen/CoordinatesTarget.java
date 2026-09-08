package org.fixture.codegen;

/**
 * A record: the implements clause has to land after the component list, not after the name.
 */
public record CoordinatesTarget(double latitude, double longitude) {
}
