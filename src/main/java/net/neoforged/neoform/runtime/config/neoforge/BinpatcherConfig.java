package net.neoforged.neoform.runtime.config.neoforge;

import java.util.List;

public record BinpatcherConfig(
        String version,
        List<String> args) {}
