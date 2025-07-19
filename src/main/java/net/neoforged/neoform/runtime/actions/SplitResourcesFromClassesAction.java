package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.srgutils.IMappingFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Copies a Jar file while applying a filename filter.
 * <p>Optionally, this also {@link #generateSplitManifest creates and injects} a {@code MANIFEST.MF} file that details files that are exclusive
 * to the distribution of Minecraft being processed by this action.
 */
public final class SplitResourcesFromClassesAction extends BuiltInAction {

    /**
     * @see #generateSplitManifest
     */
    public static final String INPUT_OTHER_DIST_JAR = "otherDistJar";
    /**
     * @see #generateSplitManifest
     */
    public static final String INPUT_MAPPINGS = "mappings";

    /**
     * Use a fixed timestamp for the manifest entry.
     */
    private static final LocalDateTime MANIFEST_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0, 0);

    /**
     * Patterns for filenames that should not be written to either output jar.
     */
    private final List<Pattern> denyListPatterns = new ArrayList<>();

    /**
     * When non-null, the action expects additional inputs ({@link #INPUT_OTHER_DIST_JAR} and {@link #INPUT_MAPPINGS})
     * pointing to the Jar file of the *other* distribution (i.e. this action processes the client resources,
     * then the other distribution jar is the server jar).
     * The mapping file is required to produce a Manifest using named file names instead of obfuscated names.
     */
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

        // TODO: new ZipFile is deprecated
        try (var jar = new ZipFile(inputJar.toFile());
             var classesFileOut = new BufferedOutputStream(Files.newOutputStream(classesJar));
             var resourcesFileOut = new BufferedOutputStream(Files.newOutputStream(resourcesJar));
             var classesJarOut = new ZipArchiveOutputStream(classesFileOut);
             var resourcesJarOut = new ZipArchiveOutputStream(resourcesFileOut);
        ) {
            if (generateDistManifestSettings != null) {
                generateDistSourceManifest(
                        generateDistManifestSettings.distId(),
                        jar,
                        generateDistManifestSettings.otherDistId(),
                        otherDistJarPath,
                        mappingsPath,
                        resourcesJarOut
                );
            }

            var entries = jar.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue; // For simplicity, we ignore directories completely
                }

                // If this task generates its own manifest, ignore any manifests found in the input jar
                if (generateDistManifestSettings != null && entry.getName().equals(JarFile.MANIFEST_NAME)) {
                    continue;
                }

                var filename = entry.getName();

                // Skip anything that looks like a signature file
                if (denyPredicate.test(filename)) {
                    continue;
                }

                var destinationStream = filename.endsWith(".class") ? classesJarOut : resourcesJarOut;

                destinationStream.addRawArchiveEntry(entry, jar.getRawInputStream(entry));
            }
        }
    }

    private static void generateDistSourceManifest(String distId,
                                                   ZipFile jar,
                                                   String otherDistId,
                                                   Path otherDistJarPath,
                                                   Path mappingsPath,
                                                   ZipArchiveOutputStream resourcesJarOut) throws IOException {
        var mappings = mappingsPath != null ? IMappingFile.load(mappingsPath.toFile()) : null;

        // Use the time-stamp of either of the two input files (whichever is newer)
        var ourFiles = getFileIndex(jar);
        ourFiles.remove(JarFile.MANIFEST_NAME);
        Set<String> theirFiles;
        try (var otherDistJar = new ZipFile(otherDistJarPath.toFile())) {
            theirFiles = getFileIndex(otherDistJar);
        }
        theirFiles.remove(JarFile.MANIFEST_NAME);

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Minecraft-Dists", distId + " " + otherDistId);

        addSourceDistEntries(ourFiles, theirFiles, distId, mappings, manifest);
        addSourceDistEntries(theirFiles, ourFiles, otherDistId, mappings, manifest);

        var manifestEntry = new ZipArchiveEntry(JarFile.MANIFEST_NAME);
        manifestEntry.setTimeLocal(MANIFEST_TIME);
        resourcesJarOut.putArchiveEntry(manifestEntry);
        manifest.write(resourcesJarOut);
        resourcesJarOut.closeArchiveEntry();
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

    private static Set<String> getFileIndex(ZipFile zipFile) {
        var result = new HashSet<String>();

        var entries = zipFile.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
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
     * <p>This adds required inputs {@link #INPUT_MAPPINGS} and {@link #INPUT_OTHER_DIST_JAR} to this action.
     * <p>Common values for distributions are {@code client} and {@code server}.
     *
     * @param distId      The name for the distribution that the main input file is from. It is used in the
     *                    generated manifest for files that are only present in the main input, but not in the
     *                    {@linkplain #INPUT_OTHER_DIST_JAR jar file of the other distribution}.
     * @param otherDistId The name for the Minecraft distribution for the jar file given in {@link #INPUT_OTHER_DIST_JAR}.
     *                    It is used in the generated manifest for files that are only present in that jar file.
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
        // TODO: remove :P
        ck.add("force rerun", "" + Math.random());
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
