package net.neoforged.neoform.runtime.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a minimal NeoForge userdev archive around a {@link NeoFormFixture} for tests of NeoForge-specific processing.
 * The wrapped fixture supplies the base Minecraft pipeline; this fixture adds NeoForge sources, classes and transforms.
 */
final class NeoForgeFixture implements NfrtFixture {
    private final NeoFormFixture neoForm;
    private final Map<String, String> sources;
    private final Map<String, byte[]> universalEntries;
    private final List<String> libraries;
    private final Map<String, String> accessTransformers;
    private final byte[] binaryPatch;
    private final Map<String, String> patches;
    private final String patchesOriginalPrefix;
    private final String patchesModifiedPrefix;
    private final Map<String, String> sideAnnotationStrippers;

    private NeoForgeFixture(NeoFormFixture neoForm,
                            Map<String, String> sources,
                            Map<String, byte[]> universalEntries,
                            List<String> libraries,
                            Map<String, String> accessTransformers,
                            byte[] binaryPatch,
                            Map<String, String> patches,
                            String patchesOriginalPrefix,
                            String patchesModifiedPrefix,
                            Map<String, String> sideAnnotationStrippers) {
        this.neoForm = neoForm;
        this.sources = Map.copyOf(sources);
        this.universalEntries = Map.copyOf(universalEntries);
        this.libraries = List.copyOf(libraries);
        this.accessTransformers = Map.copyOf(accessTransformers);
        this.binaryPatch = binaryPatch.clone();
        this.patches = Map.copyOf(patches);
        this.patchesOriginalPrefix = patchesOriginalPrefix;
        this.patchesModifiedPrefix = patchesModifiedPrefix;
        this.sideAnnotationStrippers = Map.copyOf(sideAnnotationStrippers);
    }

    static Builder builder(NeoFormFixture neoForm) {
        return new Builder(neoForm);
    }

    @Override
    public Materialized materialize(Context context) throws IOException {
        var base = neoForm.materializeNeoForm(context);
        var userdev = createUserdev(context, base.neoFormArchive());
        return new Materialized(base.launcherDirectory(), "--neoforge=" + userdev);
    }

    private Path createUserdev(Context context, Path neoFormArchive) throws IOException {
        var testDirectory = context.testDirectory();
        var sourcesJar = testDirectory.resolve("neoforge-sources.jar");
        NfrtFixtureSupport.writeTextZip(sourcesJar, sources);
        var universalJar = testDirectory.resolve("neoforge-universal.jar");
        NfrtFixtureSupport.writeZip(universalJar, universalEntries);

        var config = new LinkedHashMap<String, Object>();
        config.put("spec", 2);
        config.put("mcp", neoFormArchive.toString());
        config.put("ats", "ats/");
        config.put("binpatches", "binary/patches.lzma");
        config.put("binpatcher", Map.of(
                "version", context.javaSourceTransformerCoordinate(),
                "args", List.of(
                        "--in-format", "ARCHIVE",
                        "--out-format", "ARCHIVE",
                        "{clean}", "{output}"
                )
        ));
        config.put("patches", "patches/");
        config.put("sources", sourcesJar.toString());
        config.put("universal", universalJar.toString());
        config.put("patchesOriginalPrefix", patchesOriginalPrefix);
        config.put("patchesModifiedPrefix", patchesModifiedPrefix);
        config.put("runs", Map.of());
        config.put("libraries", libraries);
        config.put("modules", List.of());
        config.put("sass", List.copyOf(sideAnnotationStrippers.keySet()));

        var userdev = testDirectory.resolve("neoforge-userdev.jar");
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("config.json", NfrtFixtureSupport.jsonBytes(config));
        entries.put("ats/", new byte[0]);
        if (accessTransformers.isEmpty()) {
            entries.put("ats/.keep", new byte[0]);
        }
        for (var entry : accessTransformers.entrySet()) {
            entries.put("ats/" + entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        entries.put("binary/patches.lzma", binaryPatch);
        entries.put("patches/", new byte[0]);
        for (var entry : patches.entrySet()) {
            entries.put("patches/" + entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        for (var entry : sideAnnotationStrippers.entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        NfrtFixtureSupport.writeZip(userdev, entries);
        return userdev;
    }

    static final class Builder {
        private final NeoFormFixture neoForm;
        private final Map<String, String> sources = new LinkedHashMap<>();
        private final Map<String, byte[]> universalEntries = new LinkedHashMap<>();
        private final List<String> libraries = new ArrayList<>();
        private final Map<String, String> accessTransformers = new LinkedHashMap<>();
        private byte[] binaryPatch = new byte[0];
        private final Map<String, String> patches = new LinkedHashMap<>();
        private String patchesOriginalPrefix = "a/";
        private String patchesModifiedPrefix = "b/";
        private final Map<String, String> sideAnnotationStrippers = new LinkedHashMap<>();

        private Builder(NeoFormFixture neoForm) {
            this.neoForm = Objects.requireNonNull(neoForm);
        }

        Builder source(String path, String content) {
            sources.put(path, content);
            return this;
        }

        Builder universalEntry(String path, String content) {
            return universalEntry(path, content.getBytes(StandardCharsets.UTF_8));
        }

        Builder universalEntry(String path, byte[] content) {
            universalEntries.put(path, content.clone());
            return this;
        }

        Builder library(String coordinate) {
            libraries.add(Objects.requireNonNull(coordinate));
            return this;
        }

        Builder accessTransformer(String content) {
            var path = "access-transformer-" + (accessTransformers.size() + 1) + ".cfg";
            accessTransformers.put(path, content);
            return this;
        }

        Builder binaryPatch(byte[] content) {
            binaryPatch = Objects.requireNonNull(content).clone();
            return this;
        }

        Builder patch(String path, String content) {
            patches.put(path, content);
            return this;
        }

        Builder patchPrefixes(String originalPrefix, String modifiedPrefix) {
            patchesOriginalPrefix = Objects.requireNonNull(originalPrefix);
            patchesModifiedPrefix = Objects.requireNonNull(modifiedPrefix);
            return this;
        }

        Builder sideAnnotationStripper(String content) {
            var path = "sas/side-annotation-stripper-" + (sideAnnotationStrippers.size() + 1) + ".txt";
            sideAnnotationStrippers.put(path, content);
            return this;
        }

        NeoForgeFixture build() {
            return new NeoForgeFixture(
                    neoForm,
                    sources,
                    universalEntries,
                    libraries,
                    accessTransformers,
                    binaryPatch,
                    patches,
                    patchesOriginalPrefix,
                    patchesModifiedPrefix,
                    sideAnnotationStrippers
            );
        }
    }
}
