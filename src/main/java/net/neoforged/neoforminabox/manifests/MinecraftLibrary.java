package net.neoforged.neoforminabox.manifests;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record MinecraftLibrary(@SerializedName("name") String artifactId, Downloads downloads, List<Rule> rules) {
    public MinecraftLibrary {
        Objects.requireNonNull(artifactId, "name");
        rules = Objects.requireNonNullElseGet(rules, List::of);
    }

    public boolean rulesMatch() {
        if (rules.isEmpty()) {
            return true;
        }

        for (Rule rule : rules) {
            var ruleApplies = rule.evaluate();
            if (!ruleApplies && rule.action() == RuleAction.ALLOWED) {
                return false;
            } else if (ruleApplies && rule.action() == RuleAction.DISALLOWED) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    public MinecraftDownload getArtifactDownload() {
        return downloads != null ? downloads.artifact : null;
    }

    public record Downloads(MinecraftDownload artifact) {
    }

    @Override
    public String toString() {
        return artifactId;
    }
}
