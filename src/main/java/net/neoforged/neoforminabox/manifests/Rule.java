package net.neoforged.neoforminabox.manifests;


import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

public record Rule(
        RuleAction action,
        Map<String, Boolean> features,
        @Nullable OsCondition os
) {
    public Rule {
        Objects.requireNonNull(action);
        features = Objects.requireNonNullElseGet(features, Map::of);
    }

    public boolean evaluate() {
        return features.isEmpty() && (os == null || os.platformMatches());
    }
}

