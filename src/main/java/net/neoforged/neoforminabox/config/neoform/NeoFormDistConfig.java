package net.neoforged.neoforminabox.config.neoform;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Facade over a NeoForm configuration file and selected distribution.
 */
public class NeoFormDistConfig {
    private final NeoFormConfig config;

    private final String dist;

    public NeoFormDistConfig(NeoFormConfig config, String dist) {
        this.config = config;
        this.dist = dist;
    }

    public String minecraftVersion() {
        return config.minecraftVersion();
    }

    public List<NeoFormStep> steps() {
        return Objects.requireNonNull(config.steps().get(dist));
    }

    @Nullable
    public NeoFormFunction getFunction(String id) {
        return config.functions().get(id);
    }

    public boolean hasData(String id) {
        return config.data().containsKey(id);
    }

    public String getDataPathInZip(String id) {
        return config.getDataPathInZip(id, dist);
    }

    public List<String> libraries() {
        return config.libraries().getOrDefault(dist, List.of());
    }
}
