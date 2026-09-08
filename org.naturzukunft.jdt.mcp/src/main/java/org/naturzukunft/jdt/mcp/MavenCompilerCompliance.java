package org.naturzukunft.jdt.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The Java compiler compliance level a Maven module declares for itself, resolved from its
 * {@code pom.xml}.
 *
 * <p>Resolution order (first hit wins), per module POM: {@code maven.compiler.release} property,
 * then {@code maven.compiler.source}/{@code maven.compiler.target} properties, then the
 * {@code maven-compiler-plugin} {@code <configuration>} ({@code <release>}, falling back to
 * {@code <source>}/{@code <target>}; both {@code <plugins>} and {@code <pluginManagement>} are
 * checked). Property values may reference another property of the same POM with
 * {@code ${...}} -- resolved one level deep. When a module's own POM has none of these, its
 * {@code <parent>} POM (via {@code <relativePath>}, default {@code ../pom.xml}) is consulted the
 * same way, walking up the parent chain.
 *
 * <p>This class is pure Java (no Eclipse/OSGi dependency) so it can be unit-tested outside the
 * Tycho reactor. Mapping the resolved {@link #version()} onto what the running JDT actually
 * supports (e.g. clamping an unknown future release) is the caller's job -- see
 * {@code ProjectImporter#applyCompilerCompliance}.
 *
 * @param version     normalized compliance string in JDT's own format (e.g. {@code "1.8"},
 *                     {@code "17"}, {@code "25"})
 * @param propertyKey the Maven property or plugin configuration element the value came from
 *                     (e.g. {@code "maven.compiler.release"}), for logging
 * @param pomFile      the POM the value was actually found in (module POM or an ancestor)
 */
public record MavenCompilerCompliance(String version, String propertyKey, Path pomFile) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern LEGACY_VERSION = Pattern.compile("1\\.[1-8]");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");
    private static final String COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

    /**
     * Resolves the compiler compliance for the module POM at {@code pomFile}, walking up
     * {@code <parent>} POMs when the module itself declares none.
     *
     * @return the resolved compliance, or {@link Optional#empty()} when no
     *         {@code maven.compiler.*} value is found anywhere in the POM chain
     */
    public static Optional<MavenCompilerCompliance> resolve(Path pomFile) {
        return resolve(pomFile, new HashSet<>());
    }

    private static Optional<MavenCompilerCompliance> resolve(Path pomFile, Set<Path> visited) {
        Path normalized = pomFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !visited.add(normalized)) {
            return Optional.empty();
        }

        Document doc;
        try {
            doc = parse(normalized);
        } catch (Exception e) {
            return Optional.empty();
        }

        Element root = doc.getDocumentElement();
        Map<String, String> properties = readProperties(root);

        Optional<MavenCompilerCompliance> ownValue = fromReleaseProperty(properties, normalized)
                .or(() -> fromSourceTargetProperties(properties, normalized))
                .or(() -> fromCompilerPluginConfiguration(root, properties, normalized));
        if (ownValue.isPresent()) {
            return ownValue;
        }

        return parentPomOf(root, normalized).flatMap(parentPom -> resolve(parentPom, visited));
    }

    private static Optional<MavenCompilerCompliance> fromReleaseProperty(Map<String, String> properties,
            Path pomFile) {
        return valueOf(properties, "maven.compiler.release", properties, pomFile);
    }

    private static Optional<MavenCompilerCompliance> fromSourceTargetProperties(Map<String, String> properties,
            Path pomFile) {
        return valueOf(properties, "maven.compiler.source", properties, pomFile)
                .or(() -> valueOf(properties, "maven.compiler.target", properties, pomFile));
    }

    private static Optional<MavenCompilerCompliance> valueOf(Map<String, String> source, String key,
            Map<String, String> properties, Path pomFile) {
        String raw = source.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        return normalize(resolvePlaceholder(raw, properties)).map(v -> new MavenCompilerCompliance(v, key, pomFile));
    }

    private static Optional<MavenCompilerCompliance> fromCompilerPluginConfiguration(Element root,
            Map<String, String> properties, Path pomFile) {
        for (Element build : childElements(root, "build")) {
            Optional<MavenCompilerCompliance> viaPlugins = compilerPluginConfig(build, "plugins", properties, pomFile);
            if (viaPlugins.isPresent()) {
                return viaPlugins;
            }
            Optional<MavenCompilerCompliance> viaManagement =
                    childElements(build, "pluginManagement").stream()
                            .map(pm -> compilerPluginConfig(pm, "plugins", properties, pomFile))
                            .filter(Optional::isPresent)
                            .findFirst()
                            .orElse(Optional.empty());
            if (viaManagement.isPresent()) {
                return viaManagement;
            }
        }
        return Optional.empty();
    }

    private static Optional<MavenCompilerCompliance> compilerPluginConfig(Element parent, String pluginsTag,
            Map<String, String> properties, Path pomFile) {
        for (Element plugins : childElements(parent, pluginsTag)) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (!COMPILER_PLUGIN_ARTIFACT_ID.equals(artifactId)) {
                    continue;
                }
                for (Element configuration : childElements(plugin, "configuration")) {
                    Optional<MavenCompilerCompliance> release =
                            valueOf(elementTexts(configuration), "release", properties, pomFile);
                    if (release.isPresent()) {
                        return release;
                    }
                    Optional<MavenCompilerCompliance> sourceOrTarget =
                            valueOf(elementTexts(configuration), "source", properties, pomFile)
                                    .or(() -> valueOf(elementTexts(configuration), "target", properties, pomFile));
                    if (sourceOrTarget.isPresent()) {
                        return sourceOrTarget;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> parentPomOf(Element root, Path pomFile) {
        for (Element parent : childElements(root, "parent")) {
            String relativePath = childText(parent, "relativePath");
            if (relativePath != null && relativePath.isBlank()) {
                return Optional.empty();
            }
            String effectiveRelativePath = relativePath != null ? relativePath : "../pom.xml";
            Path parentPom = pomFile.getParent().resolve(effectiveRelativePath).normalize();
            if (Files.isDirectory(parentPom)) {
                parentPom = parentPom.resolve("pom.xml");
            }
            return Optional.of(parentPom);
        }
        return Optional.empty();
    }

    /** Resolves a single {@code ${key}} placeholder against {@code properties}, one level deep. */
    private static String resolvePlaceholder(String raw, Map<String, String> properties) {
        Matcher matcher = PLACEHOLDER.matcher(raw.trim());
        if (matcher.matches()) {
            String resolved = properties.get(matcher.group(1));
            return resolved != null ? resolved : raw;
        }
        return raw;
    }

    /**
     * Normalizes a raw Maven compiler value to JDT's compliance string format: legacy
     * {@code "1.5"}..{@code "1.9"} pass through as-is, a bare major version {@code <= 8} becomes
     * {@code "1.<n>"} (JDT's own convention, e.g. {@code "8"} -&gt; {@code "1.8"}), and
     * {@code 9} and above pass through as the plain number (matching JDT's own compliance
     * strings for those releases, e.g. {@code "25"}). Anything unrecognized (e.g. an unresolved
     * {@code ${...}} placeholder) is passed through unchanged -- the caller decides, via
     * {@code JavaCore.isSupportedJavaVersion}, whether that is usable.
     */
    private static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (LEGACY_VERSION.matcher(trimmed).matches()) {
            return Optional.of(trimmed);
        }
        Matcher leading = LEADING_DIGITS.matcher(trimmed);
        if (leading.find()) {
            int major = Integer.parseInt(leading.group(1));
            return Optional.of(major <= 8 ? "1." + major : String.valueOf(major));
        }
        return Optional.of(trimmed);
    }

    private static Document parse(Path pomFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(pomFile.toFile());
    }

    /** Direct child {@code <properties>} of {@code root} as a flat key/value map. */
    private static Map<String, String> readProperties(Element root) {
        Map<String, String> properties = new HashMap<>();
        for (Element propertiesEl : childElements(root, "properties")) {
            properties.putAll(elementTexts(propertiesEl));
        }
        return properties;
    }

    /** Direct child elements of {@code parent}, as a tag-name -&gt; text-content map. */
    private static Map<String, String> elementTexts(Element parent) {
        Map<String, String> texts = new HashMap<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el) {
                texts.put(el.getTagName(), el.getTextContent().trim());
            }
        }
        return texts;
    }

    private static String childText(Element parent, String tagName) {
        for (Element el : childElements(parent, tagName)) {
            return el.getTextContent().trim();
        }
        return null;
    }

    /** Direct child elements of {@code parent} matching {@code tagName} (not descendants). */
    private static java.util.List<Element> childElements(Element parent, String tagName) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && tagName.equals(el.getTagName())) {
                result.add(el);
            }
        }
        return result;
    }
}
