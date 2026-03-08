/**
 * Simple class in a plain Java project (no build tool) --
 * for testing jdt_import_project with plain directories containing .java files.
 */
public class PlainService {

    public static void main(String[] args) {
        System.out.println("Hello from plain Java project");
    }

    public String echo(String input) {
        return input;
    }
}
