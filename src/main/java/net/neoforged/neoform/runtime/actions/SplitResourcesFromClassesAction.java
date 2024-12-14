package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies a Jar file while applying a filename filter.
 */
public final class SplitResourcesFromClassesAction extends BuiltInAction {

    public static final String INPUT_OTHER_DIST_JAR = "otherDistJar";
    public static final String INPUT_MAPPINGS = "mappings";

    /**
     * Use a fixed timestamp for the manifest entry.
     */
    private static final LocalDateTime MANIFEST_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0, 0);

    /**
     * Patterns for filenames that should not be written to either output jar.
     */
    private final List<Pattern> denyListPatterns = new ArrayList<>();

    @Nullable
    private GenerateDistManifestSettings generateDistManifestSettings;

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputJar = environment.getRequiredInputPath("input");
        Path otherDistJarPath = null;
        Path mappingsPath = null;
        if (generateDistManifestSettings != null) {
            otherDistJarPath = environment.getRequiredInputPath(INPUT_OTHER_DIST_JAR);
            mappingsPath = environment.getRequiredInputPath(INPUT_MAPPINGS);
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

        try (var jar = new ZipFile(inputJar.toFile());
             var classesFileOut = new BufferedOutputStream(Files.newOutputStream(classesJar));
             var resourcesFileOut = new BufferedOutputStream(Files.newOutputStream(resourcesJar));
             var classesJarOut = new JarOutputStream(classesFileOut);
             var resourcesJarOut = new JarOutputStream(resourcesFileOut);
        ) {
            if (generateDistManifestSettings != null) {
                generateDistSourceManifest(
                        mappingsPath,
                        inputJar,
                        jar,
                        otherDistJarPath,
                        resourcesJarOut
                );
            }

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue; // For simplicity, we ignore directories completely
                }

                // If we injected a manifest earlier, ignore any subsequent manifests
                if (generateDistManifestSettings != null && entry.getName().equals(JarFile.MANIFEST_NAME)) {
                    continue;
                }

                var filename = entry.getName();

                // Skip anything that looks like a signature file
                if (denyPredicate.test(filename)) {
                    continue;
                }

                var destinationStream = filename.endsWith(".class") ? classesJarOut : resourcesJarOut;

                destinationStream.putNextEntry(entry);
                try (var is = jar.getInputStream(entry)) {
                    is.transferTo(destinationStream);
                }
                destinationStream.closeEntry();
            }
        }
    }

    private void generateDistSourceManifest(Path mappingsPath, Path inputJar, ZipFile jar, Path otherDistJarPath, JarOutputStream resourcesJarOut) throws IOException {
        var mappings = mappingsPath != null ? IMappingFile.load(mappingsPath.toFile()) : null;

        // Use the time-stamp of either of the two input files (whichever is newer)
        FileTime mtime = Files.getLastModifiedTime(inputJar);
        var ourFiles = getFileIndex(jar);
        ourFiles.remove(JarFile.MANIFEST_NAME);
        Set<String> theirFiles;
        try (var otherDistJar = new ZipFile(otherDistJarPath.toFile())) {
            var otherMtime = Files.getLastModifiedTime(otherDistJarPath);
            if (otherMtime.compareTo(mtime) > 0) {
                mtime = otherMtime;
            }
            theirFiles = getFileIndex(otherDistJar);
        }
        theirFiles.remove(JarFile.MANIFEST_NAME);

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Minecraft-Dists", generateDistManifestSettings.distId()
            + " " + generateDistManifestSettings.otherDistId());

        addSourceDistEntries(ourFiles, theirFiles, generateDistManifestSettings.distId(), mappings, manifest);
        addSourceDistEntries(theirFiles, ourFiles, generateDistManifestSettings.otherDistId(), mappings, manifest);

        var manifestEntry = new ZipEntry(JarFile.MANIFEST_NAME);
        manifestEntry.setLastModifiedTime(mtime);
        resourcesJarOut.putNextEntry(manifestEntry);
        manifest.write(resourcesJarOut);
        resourcesJarOut.closeEntry();
    }

    private static void addSourceDistEntries(Set<String> distFiles,
                                             Set<String> otherDistFiles,
                                             String dist,
                                             IMappingFile mappings,
                                             Manifest manifest) {
        for (var file : distFiles) {
            if (!otherDistFiles.contains(file)) {
                var fileAttr = new Attributes(1);
                fileAttr.putValue("Minecraft-Dist", dist);

                if (mappings != null && file.endsWith(".class")) {
                    file = mappings.remapClass(file.substring(0, file.length() - ".class".length())) + ".class";
                }
                manifest.getEntries().put(file, fileAttr);
            }
        }
    }

    private Set<String> getFileIndex(ZipFile zipFile) {
        var result = new HashSet<String>(zipFile.size());

        var entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                result.add(entry.getName());
            }
        }

        return result;
    }

    /**
     * Adds a regular expression for filenames that should be filtered out completely.
     */
    public void addDenyPatterns(String... patterns) {
        for (String pattern : patterns) {
            denyListPatterns.add(Pattern.compile(pattern));
        }
    }

    /**
     * Enable generation of a Jar manifest in the output resources jar which contains
     * entries detailing which distribution each file came from.
     * This adds new required inputs.
     */
    public void generateSplitManifest(String distId, String otherDistId) {
        generateDistManifestSettings = new GenerateDistManifestSettings(
                Objects.requireNonNull(distId, "distId"),
                Objects.requireNonNull(otherDistId, "otherDistId")
        );
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("deny patterns", denyListPatterns.stream().map(Pattern::pattern).toList());
        if (generateDistManifestSettings != null) {
            ck.add("generate dist manifest - our dist", generateDistManifestSettings.distId);
            ck.add("generate dist manifest - other dist", generateDistManifestSettings.otherDistId);
        }
    }

    private record GenerateDistManifestSettings(
            String distId,
            String otherDistId
    ) {
    }
}
