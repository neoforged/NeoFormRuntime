package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ClasspathConfiguration;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

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
    private final ClasspathConfiguration classpathConfig;

    public CreateLibrariesOptionsFile(ClasspathConfiguration classpathConfig) {
        this.classpathConfig = Objects.requireNonNull(classpathConfig, "classpathConfig");
    }

    /**
     * Writes the library list to a file.
     */
    public Path writeFile(ProcessingEnvironment environment) throws IOException {
        var classpath = classpathConfig.resolve(environment);

        var vineflowerArgs = classpath.stream().map(l -> "-e=" + l.toAbsolutePath()).toList();

        var libraryListFile = environment.getWorkspace().resolve("libraries.txt");
        Files.write(libraryListFile, vineflowerArgs);
        return libraryListFile;
    }

    public void computeCacheKey(CacheKeyBuilder ck) {
        classpathConfig.computeCacheKey("listLibraries classpath", ck);
    }

    public ClasspathConfiguration getClasspath() {
        return classpathConfig;
    }
}
