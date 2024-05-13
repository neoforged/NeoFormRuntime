package net.neoforged.neoform.runtime.manifests;

import java.util.List;

public record MinecraftArguments(List<UnresolvedArgument> game, List<UnresolvedArgument> jvm) {
}
