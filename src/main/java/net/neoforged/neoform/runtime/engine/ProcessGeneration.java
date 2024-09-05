package net.neoforged.neoform.runtime.engine;

import java.util.List;

/**
 * Over the life of Forge and NeoForge, there were several overhauls of the process to generate
 * usable development Minecraft artifacts.
 */
public enum ProcessGeneration {
    MCP_SINCE_1_12,
    /**
     * For (Neo)Forge 1.20.1 and below, we have to remap method and field names from
     * SRG to official names for development.
     */
    MCP_SINCE_1_17,
    NEOFORM_SINCE_1_20_2;

    static ProcessGeneration fromMinecraftVersion(String minecraftVersion) {
        return switch (minecraftVersion) {
            case "1.12.2" -> MCP_SINCE_1_12;
            case "1.17", "1.17.1", "1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20",
                 "1.20.1" -> MCP_SINCE_1_17;
            default -> NEOFORM_SINCE_1_20_2;
        };
    }

    /**
     * Does the Minecraft source code that MCP/NeoForm creates use SRG names?
     */
    public boolean sourcesUseSrgNames() {
        return compareTo(NEOFORM_SINCE_1_20_2) < 0;
    }

    /**
     * Allows additional resources to be completely removed from Minecraft jars before processing them.
     */
    List<String> getAdditionalDenyListForMinecraftJars() {
        return switch (this) {
            /*
             * In these Minecraft Versions, the server jar just contains the shaded libraries,
             * and the tooling does not strip them.
             */
            case MCP_SINCE_1_12 -> List.of(
                    "com/google/.*",
                    "io/.*",
                    "it/.*",
                    "javax/.*",
                    "org/.*"
            );
            default -> List.of();
        };
    }
}
