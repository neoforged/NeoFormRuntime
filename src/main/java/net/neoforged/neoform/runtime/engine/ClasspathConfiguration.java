package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import net.neoforged.neoform.runtime.manifests.MinecraftLibrary;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable configuration for a classpath.
 */
public final class ClasspathConfiguration {
    @Unmodifiable
    private final List<ClasspathItem> entries;
    private final boolean includeMinecraftLibraries;

    private ClasspathConfiguration(boolean includeMinecraftLibraries, Collection<ClasspathItem> entries) {
        var normalizedEntries = new ArrayList<ClasspathItem>();
        for (var entry : entries) {
            add(normalizedEntries, Objects.requireNonNull(entry, "entry"));
        }
        this.entries = List.copyOf(normalizedEntries);
        this.includeMinecraftLibraries = includeMinecraftLibraries;
    }

    public static ClasspathConfiguration of(Collection<ClasspathItem> entries) {
        return new ClasspathConfiguration(false, entries);
    }

    /**
     * Creates a classpath that adds the Minecraft libraries from the version manifest.
     */
    public static ClasspathConfiguration ofMinecraft() {
        return new ClasspathConfiguration(true, List.of());
    }

    /**
     * Creates a classpath that adds the Minecraft libraries from the version manifest to the given entries.
     */
    public static ClasspathConfiguration ofMinecraftAnd(Collection<ClasspathItem> entries) {
        return new ClasspathConfiguration(true, entries);
    }

    @VisibleForTesting
    @Unmodifiable
    List<ClasspathItem> entries() {
        return entries;
    }

    private static void addMinecraftLibraries(List<ClasspathItem> entries, Collection<MinecraftLibrary> libraries) {
        for (var library : libraries) {
            if (library.rulesMatch() && library.getArtifactDownload() != null) {
                add(entries, ClasspathItem.of(library));
            }
        }
    }

    private static void add(List<ClasspathItem> entries, ClasspathItem classpathItem) {
        // Ensure that previously added libraries of the same group:artifact:classifier are overridden to avoid
        // the same library from being present on the classpath twice.
        var coordinate = getMavenCoordinate(classpathItem);
        if (coordinate != null) {
            entries.removeIf(existingItem -> {
                var existingCoord = getMavenCoordinate(existingItem);
                return existingCoord != null && existingCoord.equalsWithoutVersion(coordinate);
            });
        }

        entries.add(classpathItem);
    }

    @Nullable
    private static MavenCoordinate getMavenCoordinate(ClasspathItem classpathItem) {
        return switch (classpathItem) {
            case ClasspathItem.MavenCoordinateItem mavenCoordinateItem -> mavenCoordinateItem.coordinate();
            case ClasspathItem.MinecraftLibraryItem minecraftLibraryItem -> minecraftLibraryItem.library().getMavenCoordinate();
            default -> null;
        };
    }

    public void computeCacheKey(String prefix, CacheKeyBuilder ck) {
        ck.add(prefix + " includes Minecraft libraries", String.valueOf(includeMinecraftLibraries));
        for (int i = 0; i < entries.size(); i++) {
            var component = String.format(Locale.ROOT, "%s[%03d]", prefix, i);
            var item = entries.get(i);

            switch (item) {
                case ClasspathItem.MavenCoordinateItem(MavenCoordinate coordinate, URI uri) -> {
                    if (uri != null) {
                        ck.add(component, coordinate + " from " + uri);
                    } else {
                        ck.add(component, coordinate.toString());
                    }
                }
                case ClasspathItem.MinecraftLibraryItem(MinecraftLibrary library) -> {
                    var artifactDownload = library.getArtifactDownload();
                    if (artifactDownload != null) {
                        ck.add(component, library.artifactId() + " [" + artifactDownload.checksum() + "]");
                    } else {
                        ck.add(component, library.artifactId());
                    }
                }
                case ClasspathItem.PathItem(Path path) -> ck.addPath(component, path);
                case ClasspathItem.NodeOutputItem(NodeOutput output) -> ck.addPath(component, output.getResultPath());
            }
        }
    }

    /**
     * Resolves the configured classpath. Explicit entries take precedence over Minecraft libraries with the same
     * coordinates.
     */
    public List<Path> resolve(ProcessingEnvironment environment) throws IOException {
        List<ClasspathItem> effectiveClasspath;
        if (includeMinecraftLibraries) {
            var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);
            var entriesWithMinecraftLibraries = new ArrayList<ClasspathItem>();
            addMinecraftLibraries(entriesWithMinecraftLibraries, versionManifest.libraries());
            entries.forEach(entry -> add(entriesWithMinecraftLibraries, entry));
            effectiveClasspath = entriesWithMinecraftLibraries;
        } else {
            effectiveClasspath = entries;
        }

        return environment.getArtifactManager().resolveClasspath(effectiveClasspath);
    }
}
