package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small bit of logic equivalent to the {@code listLibraries} NeoForm step.
 * {@code listLibraries} does not exist as a separate step in NFRT; rather we fold it into the steps that use it.
 */
public class ListLibraries {
    private ExtensibleClasspath classpath = new ExtensibleClasspath();

    /**
     * Writes the library list to a file.
     */
    public Path writeFile(ProcessingEnvironment environment) throws IOException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        var effectiveClasspath = classpath.copy();
        effectiveClasspath.addMinecraftLibraries(versionManifest.libraries());
        var classpath = environment.getArtifactManager().resolveClasspath(effectiveClasspath.getEffectiveClasspath());

        var vineflowerArgs = classpath.stream().map(l -> "-e=" + l.toAbsolutePath()).toList();

        var libraryListFile = environment.getWorkspace().resolve("libraries.txt");
        Files.write(libraryListFile, vineflowerArgs);
        return libraryListFile;
    }

    public void computeCacheKey(CacheKeyBuilder ck) {
        classpath.computeCacheKey("listLibraries classpath", ck);
    }

    public ExtensibleClasspath getClasspath() {
        return classpath;
    }

    public void setClasspath(ExtensibleClasspath classpath) {
        this.classpath = classpath;
    }
}
