package net.neoforged.neoform.runtime.utils;

import java.io.IOException;
import java.util.Objects;
import java.util.Properties;

/**
 * We use this enumeration and property file so that the Gradle build-script can easily
 * look up the versions we use and expose them in a separate Gradle dependency list.
 * It also makes it far easier to integrate a renovate bot.
 */
public enum ToolCoordinate {
    JAVA_SOURCE_TRANSFORMER,
    DIFF_PATCH,
    MCF_SIDE_ANNOTATION_STRIPPER,
    INSTALLER_TOOLS,
    AUTO_RENAMING_TOOL;

    private static final Properties VERSIONS;

    private MavenCoordinate version;

    static {
        try (var in = ToolCoordinate.class.getResourceAsStream("/tools.properties")) {
            if (in == null) {
                throw new IllegalStateException("Packaging error: tools.properties is missing.");
            }
            VERSIONS = new Properties();
            VERSIONS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could ont read tools.properties", e);
        }

        for (var value : values()) {
            try {
                value.version = MavenCoordinate.parse(VERSIONS.getProperty(value.name()));
            } catch (Exception e) {
                throw new IllegalStateException("Tool coordinate for " + value + " is invalid.", e);
            }
        }

        for (var property : VERSIONS.stringPropertyNames()) {
            try {
                valueOf(property);
            } catch (IllegalArgumentException ignored) {
                throw new IllegalStateException("tools.properties contains invalid key: " + property);
            }
        }
    }

    public MavenCoordinate version() {
        return Objects.requireNonNull(version, "version");
    }
}
