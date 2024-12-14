package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * Copies a Jar file while applying a filename filter.
 */
public final class SplitResourcesFromClassesAction extends BuiltInAction {

    /**
     * Use a fixed timestamp for the manifest entry.
     */
    private static final LocalDateTime MANIFEST_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0, 0);

    /**
     * Patterns for filenames that should not be written to either output jar.
     */
    private final List<Pattern> denyListPatterns = new ArrayList<>();

    /**
     * Indicates that the MANIFEST should be taken from the "manifest" input and
     * injected into the resulting resources jar file.
     */
    private boolean injectManifest;

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputJar = environment.getRequiredInputPath("input");
        Path inputManifest = null;
        if (injectManifest) {
            inputManifest = environment.getRequiredInputPath("manifest");
        }
        var classesJar = environment.getOutputPath("output");
        var resourcesJar = environment.getOutputPath("resourcesOutput");

        Predicate<String> denyPredicate = s -> false;
        if (!denyListPatterns.isEmpty()) {
            // Build a combined regular expression to speed things up
            denyPredicate = Pattern
                    .compile(denyListPatterns.stream().map(Pattern::pattern).collect(Collectors.joining("|")))
                    .asMatchPredicate();
        }

        try (var is = new JarInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
             var classesFileOut = new BufferedOutputStream(Files.newOutputStream(classesJar));
             var resourcesFileOut = new BufferedOutputStream(Files.newOutputStream(resourcesJar));
             var classesJarOut = new JarOutputStream(classesFileOut);
             var resourcesJarOut = new JarOutputStream(resourcesFileOut);
        ) {
            // If requested, write the manifest
            if (injectManifest) {
                var entry = new ZipEntry(JarFile.MANIFEST_NAME);
                entry.setTimeLocal(MANIFEST_TIME);
                resourcesJarOut.putNextEntry(entry);
                Files.copy(inputManifest, resourcesJarOut);
                resourcesJarOut.closeEntry();
            }

            // Ignore any entry that's not allowed
            JarEntry entry;
            while ((entry = is.getNextJarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue; // For simplicity, we ignore directories completely
                }

                // If we injected a manifest earlier, ignore any subsequent manifests
                if (injectManifest && entry.getName().equals(JarFile.MANIFEST_NAME)) {
                    continue;
                }

                var filename = entry.getName();

                // Skip anything that looks like a signature file
                if (denyPredicate.test(filename)) {
                    continue;
                }

                var destinationStream = filename.endsWith(".class") ? classesJarOut : resourcesJarOut;

                destinationStream.putNextEntry(entry);
                is.transferTo(destinationStream);
                destinationStream.closeEntry();
            }
        }
    }

    /**
     * Adds a regular expression for filenames that should be filtered out completely.
     */
    public void addDenyPatterns(String... patterns) {
        for (String pattern : patterns) {
            denyListPatterns.add(Pattern.compile(pattern));
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("deny patterns", denyListPatterns.stream().map(Pattern::pattern).toList());
    }

    public void setInjectManifest(boolean injectManifest) {
        this.injectManifest = injectManifest;
    }
}
