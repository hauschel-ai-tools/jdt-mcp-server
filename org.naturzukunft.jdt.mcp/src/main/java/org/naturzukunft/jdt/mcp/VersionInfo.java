package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the server version from the .version file in the installation directory.
 */
public final class VersionInfo {

    private static final String UNKNOWN = "development";
    private static String cachedVersion;

    private VersionInfo() {
    }

    /**
     * Returns the server version string (e.g. "0.2.0") or "development" if no .version file exists.
     */
    public static String getVersion() {
        if (cachedVersion != null) {
            return cachedVersion;
        }
        cachedVersion = readVersionFile();
        return cachedVersion;
    }

    private static String readVersionFile() {
        // The .version file is placed in the product root by the build.
        // At runtime, eclipse.home.location points to the installation directory.
        String eclipseHome = System.getProperty("eclipse.home.location");
        if (eclipseHome != null) {
            Path versionFile = toPath(eclipseHome);
            if (versionFile != null) {
                String version = readFile(versionFile);
                if (version != null) {
                    return version;
                }
            }
        }

        // Fallback: check osgi.install.area
        String installArea = System.getProperty("osgi.install.area");
        if (installArea != null) {
            Path versionFile = toPath(installArea);
            if (versionFile != null) {
                String version = readFile(versionFile);
                if (version != null) {
                    return version;
                }
            }
        }

        return UNKNOWN;
    }

    private static Path toPath(String location) {
        try {
            // Properties may use file: URI or plain path
            if (location.startsWith("file:")) {
                return Path.of(java.net.URI.create(location)).resolve(".version");
            }
            return Path.of(location).resolve(".version");
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                String content = Files.readString(path).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }
}
