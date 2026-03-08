package org.fixture.gradle;

/**
 * Simple class in a Gradle project -- for testing jdt_import_project with Gradle.
 */
public class GradleService {

    private String name;

    public GradleService(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String greet() {
        return "Hello from " + name;
    }
}
