package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a Vineflower options file for listing referenced jar files. This would usually be implemented in
 * the NeoForm step {@code listLibraries}.
 * <p>We strip the {@code listLibraries} step from the NeoForm config and fold it into the steps that use it instead,
 * due to cacheability issues with the supplied libraries.
 * <p>The problem with having this as a standalone node is that the output of the node includes the users home
 * directory as an absolute path, while the cache-key will shorten the user-home to ~, when the user home is moved,
 * that keeps an invalid options file (pointing to the old home directory) with an up-to-date cache key.
 */
public class CreateLibrariesOptionsFile {
    private ExtensibleClasspath classpath = new ExtensibleClasspath();

    /**
     * Writes the library list to a file.
     */
    public Path writeFile(ProcessingEnvironment environment) throws IOException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        var effectiveClasspathItems = classpath.mergeWithMinecraftLibraries(versionManifest).getEffectiveClasspath();
        var classpath = environment.getArtifactManager().resolveClasspath(effectiveClasspathItems);

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
