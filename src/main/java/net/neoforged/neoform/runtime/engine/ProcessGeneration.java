package net.neoforged.neoform.runtime.engine;

/**
 * Over the life of Forge and NeoForge, there were several overhauls of the process to generate
 * usable development Minecraft artifacts.
 */
public enum ProcessGeneration {
    /**
     * For (Neo)Forge 1.20.1 and below, we have to remap method and field names from
     * SRG to official names for development.
     */
    MCP_SINCE_1_17,
    NEOFORM_SINCE_1_20_2;

    static ProcessGeneration fromMinecraftVersion(String minecraftVersion) {
        return switch (minecraftVersion) {
            case "1.20.1" -> MCP_SINCE_1_17;
            default -> NEOFORM_SINCE_1_20_2;
        };
    }
}
