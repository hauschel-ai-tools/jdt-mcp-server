package org.fixture.badclasspath;

/**
 * Compiles fine on its own. The project still cannot be built because of the missing
 * library on the build path -- that is the point of this fixture.
 */
public class Unbuildable {

    public String describe() {
        return "build path problem fixture";
    }
}
