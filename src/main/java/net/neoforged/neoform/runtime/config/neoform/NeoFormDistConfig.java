package net.neoforged.neoform.runtime.config.neoform;

import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public String dist() {
        return dist;
    }

    public int javaVersion() {
        return config.javaVersion();
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

    public Map<String, String> getData() {
        var result = new HashMap<String, String>(config.data().size());
        for (var entry : config.data().entrySet()) {
            String value;

            // The data can be dist-specific, but does not have to be
            if (entry.getValue() instanceof String) {
                value = (String) entry.getValue();
            } else if (entry.getValue() instanceof Map<?, ?> dataMap) {
                var distSpecificData = dataMap.get(dist);
                if (distSpecificData == null) {
                    throw new IllegalArgumentException("There's no data for " + entry.getKey() + " and distribution " + dist);
                } else if (!(distSpecificData instanceof String)) {
                    throw new IllegalArgumentException("Data for " + entry.getKey() + " and distribution " + dist + " is not a String: " + distSpecificData);
                } else {
                    value = (String) distSpecificData;
                }
            } else {
                throw new IllegalArgumentException("Unexpected type for data entry " + entry.getKey() + ": " + entry.getValue());
            }

            result.put(entry.getKey(), value);
        }
        return result;
    }

    public List<MavenCoordinate> libraries() {
        return config.libraries().getOrDefault(dist, List.of());
    }
}
