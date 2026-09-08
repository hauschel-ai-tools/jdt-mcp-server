import module java.base;

/**
 * Uses a Java 25 language feature (module import declarations) so the fixture only compiles
 * clean when JDT actually treats this project at compiler compliance 25, not at whatever JVM
 * launched the headless server (issue #82).
 */
class Main {
    void main() {
        System.out.println("fixture-java25: module import + instance main compiled at release 25");
    }
}
