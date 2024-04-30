package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.manifests.MinecraftVersionManifest;

import java.io.IOException;
import java.nio.file.Path;

public record ResultRepresentation<T>(Class<T> resultClass, Loader<T> loader) {
    public static final ResultRepresentation<Path> PATH = new ResultRepresentation<>(
            Path.class,
            p -> p
    );

    public static final ResultRepresentation<MinecraftVersionManifest> MINECRAFT_VERSION_MANIFEST = new ResultRepresentation<>(
            MinecraftVersionManifest.class,
            MinecraftVersionManifest::from
    );

    @FunctionalInterface
    public interface Loader<T> {
        T load(Path path) throws IOException;
    }
}
