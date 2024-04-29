package net.neoforged.neoforminabox.config;

import java.util.Map;
import java.util.Objects;

public record NeoFormStep(String type, String name, Map<String, String> values) {
    public String getId() {
        return Objects.requireNonNull(name, type);
    }

    @Override
    public String toString() {
        return getId();
    }
}
