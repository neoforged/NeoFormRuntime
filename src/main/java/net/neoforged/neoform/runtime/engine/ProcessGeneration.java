package net.neoforged.neoform.runtime.engine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Over the life of Forge and NeoForge, there were several overhauls of the process to generate
 * usable development Minecraft artifacts.
 */
public class ProcessGeneration {
    private record MinecraftReleaseVersion(int major, int minor, int patch) implements Comparable<MinecraftReleaseVersion> {
        private static final Comparator<MinecraftReleaseVersion> COMPARATOR = Comparator
                .comparingInt(MinecraftReleaseVersion::major)
                .thenComparingInt(MinecraftReleaseVersion::minor)
                .thenComparingInt(MinecraftReleaseVersion::patch);

        private static final Pattern RELEASE_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

        @Nullable
        public static MinecraftReleaseVersion parse(String version) {
            var m = RELEASE_VERSION.matcher(version);
            if (m.matches()) {
                return new MinecraftReleaseVersion(
                        Integer.parseUnsignedInt(m.group(1)),
                        Integer.parseUnsignedInt(m.group(2)),
                        Integer.parseUnsignedInt(m.group(3))
                );
            }
            return null;
        }

        @Override
        public int compareTo(@NotNull ProcessGeneration.MinecraftReleaseVersion o) {
            return COMPARATOR.compare(this, o);
        }
    }

    private static final MinecraftReleaseVersion MC_1_17_1 = new MinecraftReleaseVersion(1, 17, 1);
    private static final MinecraftReleaseVersion MC_1_20_1 = new MinecraftReleaseVersion(1, 20, 1);

    /**
     * Indicates whether the Minecraft server jar file contains third party
     * dependencies directly in its root directory, which need to be ignored.
     */
    private final List<String> additionalDenyListForMinecraftJars = new ArrayList<>();

    /**
     * Indicates that the sources produced by MCP/NeoForm and NeoForge in this version use
     * names from an intermediary mapping file, and not the official Mapping file.
     * In those versions, to produce usable sources, we need to apply an additional
     * remapping step later. (Either to Mojang mappings, or to MCP).
     */
    private boolean sourcesUseIntermediaryNames;

    /**
     * For (Neo)Forge 1.20.1 and below, we have to remap method and field names from
     * SRG to official names for development.
     */

    static ProcessGeneration fromMinecraftVersion(String minecraftVersion) {
        var releaseVersion = MinecraftReleaseVersion.parse(minecraftVersion);

        var result = new ProcessGeneration();

        // Minecraft 1.17.1 and older directly shaded dependency class files into the server.jar
        // while newer versions use embedded jar files instead.
        // When merging the server.jar and client.jar, we need to exclude these dependency classes.
        if (isLessThanOrEqualTo(releaseVersion, MC_1_17_1)) {
            Collections.addAll(result.additionalDenyListForMinecraftJars,
                    "com/google/.*",
                    "io/.*",
                    "it/.*",
                    "javax/.*",
                    "org/.*"
            );
        }

        // In 1.20.2 and later, NeoForge switched to Mojmap at runtime and sources defined in Mojmap
        result.sourcesUseIntermediaryNames = isLessThanOrEqualTo(releaseVersion, MC_1_20_1);

        return result;
    }

    private static boolean isLessThanOrEqualTo(@Nullable MinecraftReleaseVersion releaseVersion, MinecraftReleaseVersion version) {
        if (releaseVersion == null) {
            return false; // We're working with a snapshot version, which we always use the latest processes for
        }
        return releaseVersion.compareTo(version) <= 0;
    }

    /**
     * Does the Minecraft source code that MCP/NeoForm creates use SRG names?
     */
    public boolean sourcesUseIntermediaryNames() {
        return sourcesUseIntermediaryNames;
    }

    /**
     * Allows additional resources to be completely removed from Minecraft jars before processing them.
     */
    List<String> getAdditionalDenyListForMinecraftJars() {
        return additionalDenyListForMinecraftJars;
    }
}
