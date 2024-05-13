package net.neoforged.neoform.runtime.artifacts;

import net.neoforged.neoform.runtime.manifests.MinecraftLibrary;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;

/**
 * Models that the classpath for tools like the decompiler or the compiler can come from different sources.
 */
public sealed interface ClasspathItem {
    static ClasspathItem of(Path path) {
        return new PathItem(path);
    }

    static ClasspathItem of(MinecraftLibrary library) {
        return new MinecraftLibraryItem(library);
    }

    static ClasspathItem of(MavenCoordinate mavenLibrary) {
        return new MavenCoordinateItem(mavenLibrary, null);
    }

    static ClasspathItem of(MavenCoordinate mavenLibrary, @Nullable URI repositoryBaseUrl) {
        return new MavenCoordinateItem(mavenLibrary, repositoryBaseUrl);
    }

    record MavenCoordinateItem(MavenCoordinate coordinate, @Nullable URI repositoryBaseUrl) implements ClasspathItem {
        @Override
        public String toString() {
            if (repositoryBaseUrl == null) {
                return coordinate.toString();
            } else {
                return coordinate + " from " + repositoryBaseUrl;
            }
        }
    }

    record PathItem(Path path) implements ClasspathItem {
        @Override
        public String toString() {
            return path.toString();
        }
    }

    record MinecraftLibraryItem(MinecraftLibrary library) implements ClasspathItem {
        @Override
        public String toString() {
            return library.toString();
        }
    }
}
