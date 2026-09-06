package net.neoforged.neoform.runtime.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a minimal NeoForm archive and launcher metadata for tests of the base Minecraft processing pipeline.
 * Unlike {@link NeoForgeFixture}, it represents a direct {@code --neoform} input without NeoForge userdev data.
 */
final class NeoFormFixture implements NfrtFixture {
    private final String minecraftVersion;
    private final int javaVersion;
    private final Map<String, byte[]> sources;
    private final boolean compileLauncherSources;
    private final Map<String, byte[]> launcherSources;
    private final Map<String, Path> minecraftLibraries;
    private final List<String> neoFormLibraries;
    private final LegacyMappings legacyMappings;

    private NeoFormFixture(String minecraftVersion,
                           int javaVersion,
                           Map<String, byte[]> sources,
                           boolean compileLauncherSources,
                           Map<String, byte[]> launcherSources,
                           Map<String, Path> minecraftLibraries,
                           List<String> neoFormLibraries,
                           LegacyMappings legacyMappings) {
        this.minecraftVersion = minecraftVersion;
        this.javaVersion = javaVersion;
        this.sources = Map.copyOf(sources);
        this.compileLauncherSources = compileLauncherSources;
        this.launcherSources = Map.copyOf(launcherSources);
        this.minecraftLibraries = Map.copyOf(minecraftLibraries);
        this.neoFormLibraries = List.copyOf(neoFormLibraries);
        this.legacyMappings = legacyMappings;
    }

    static Builder builder() {
        return new Builder();
    }

    @Override
    public Materialized materialize(Context context) throws IOException {
        var neoForm = materializeNeoForm(context);
        return new Materialized(neoForm.launcherDirectory(), "--neoform=" + neoForm.neoFormArchive());
    }

    NeoFormMaterialized materializeNeoForm(Context context) throws IOException {
        var testDirectory = context.testDirectory();
        var sourcesArchive = testDirectory.resolve("sources.zip");
        NfrtFixtureSupport.writeZip(sourcesArchive, sources);

        var clientArtifact = compileLauncherSources
                ? NfrtFixtureSupport.compileSources(
                        testDirectory,
                        launcherSources.isEmpty() ? sources : launcherSources,
                        javaVersion
                )
                : sourcesArchive;
        var downloads = new LinkedHashMap<String, Path>();
        downloads.put("client", clientArtifact);
        if (legacyMappings != null) {
            var officialMappings = testDirectory.resolve("client-mappings.txt");
            Files.writeString(officialMappings, legacyMappings.officialMappings());
            downloads.put("client_mappings", officialMappings);

            var intermediaryMappings = testDirectory.resolve("server-mappings.txt");
            Files.writeString(intermediaryMappings, legacyMappings.intermediaryMappings());
            downloads.put("server_mappings", intermediaryMappings);
        }

        var launcherDirectory = NfrtFixtureSupport.createLauncherDirectory(
                testDirectory,
                minecraftVersion,
                downloads,
                minecraftLibraries
        );
        var neoFormArchive = createNeoFormArchive(context, sourcesArchive);
        return new NeoFormMaterialized(launcherDirectory, neoFormArchive);
    }

    private Path createNeoFormArchive(Context context, Path sourcesArchive) throws IOException {
        var steps = new ArrayList<Map<String, String>>();
        steps.add(Map.of("type", "downloadJson"));
        steps.add(Map.of("type", "downloadClient"));
        steps.add(Map.of("type", "strip", "name", "stripClient", "input", "{downloadClientOutput}"));
        if (legacyMappings != null) {
            steps.add(Map.of("type", "downloadClientMappings"));
            steps.add(Map.of("type", "downloadServerMappings", "name", "mergeMappings"));
        }
        steps.add(Map.of(
                "type", "copyFixtureSources",
                "name", "decompile",
                "input", "{stripClientOutput}",
                "fixtureInput", "{fixtureSources}"
        ));
        var patchInput = "{decompileOutput}";
        if (legacyMappings != null) {
            steps.add(Map.of("type", "inject", "input", patchInput));
            patchInput = "{injectOutput}";
        }
        steps.add(Map.of("type", "copySources", "name", "patch", "input", patchInput));

        var copyArguments = List.of(
                "--in-format", "ARCHIVE",
                "--out-format", "ARCHIVE",
                "{input}", "{output}"
        );
        var config = new LinkedHashMap<String, Object>();
        config.put("spec", 1);
        config.put("version", minecraftVersion);
        config.put("official", true);
        config.put("java_target", javaVersion);
        config.put("encoding", "UTF-8");
        var data = new LinkedHashMap<String, Object>();
        data.put("fixtureSources", "sources.zip");
        if (legacyMappings != null) {
            data.put("inject", "inject/");
        }
        config.put("data", data);
        config.put("steps", Map.of("joined", steps));
        config.put("functions", Map.of(
                "copyFixtureSources", Map.of(
                        "version", context.javaSourceTransformerCoordinate(),
                        "args", List.of(
                                "--in-format", "ARCHIVE",
                                "--out-format", "ARCHIVE",
                                "--libraries-list", "{listLibrariesOutput}",
                                "{fixtureInput}", "{output}"
                        )
                ),
                "copySources", Map.of(
                        "version", context.javaSourceTransformerCoordinate(),
                        "args", copyArguments
                )
        ));
        config.put("libraries", Map.of("joined", neoFormLibraries));

        var neoForm = context.testDirectory().resolve("neoform.zip");
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("config.json", NfrtFixtureSupport.jsonBytes(config));
        entries.put("sources.zip", Files.readAllBytes(sourcesArchive));
        if (legacyMappings != null) {
            entries.put("inject/", new byte[0]);
        }
        NfrtFixtureSupport.writeZip(neoForm, entries);
        return neoForm;
    }

    record NeoFormMaterialized(Path launcherDirectory, Path neoFormArchive) {
    }

    static final class Builder {
        private final Map<String, byte[]> sources = new LinkedHashMap<>();
        private final Map<String, byte[]> launcherSources = new LinkedHashMap<>();
        private final Map<String, Path> minecraftLibraries = new LinkedHashMap<>();
        private final List<String> neoFormLibraries = new ArrayList<>();
        private String minecraftVersion = "1.21";
        private int javaVersion = 21;
        private boolean compileLauncherSources;
        private LegacyMappings legacyMappings;

        private Builder() {
        }

        Builder source(String path, String content) {
            return source(path, content.getBytes(StandardCharsets.UTF_8));
        }

        Builder source(String path, byte[] content) {
            sources.put(path, content.clone());
            return this;
        }

        Builder minecraftVersion(String minecraftVersion) {
            this.minecraftVersion = Objects.requireNonNull(minecraftVersion);
            return this;
        }

        Builder javaVersion(int javaVersion) {
            if (javaVersion <= 0) {
                throw new IllegalArgumentException("Java version must be positive");
            }
            this.javaVersion = javaVersion;
            return this;
        }

        Builder compileLauncherSources() {
            compileLauncherSources = true;
            return this;
        }

        /**
         * Adds a source used only to compile the launcher client artifact, independently of the decompiled sources.
         */
        Builder launcherSource(String path, String content) {
            return launcherSource(path, content.getBytes(StandardCharsets.UTF_8));
        }

        Builder launcherSource(String path, byte[] content) {
            launcherSources.put(path, content.clone());
            compileLauncherSources = true;
            return this;
        }

        Builder minecraftLibrary(String coordinate, Path artifact) {
            minecraftLibraries.put(Objects.requireNonNull(coordinate), Objects.requireNonNull(artifact));
            return this;
        }

        Builder neoFormLibrary(String coordinate) {
            neoFormLibraries.add(Objects.requireNonNull(coordinate));
            return this;
        }

        Builder legacyMappings(LegacyMappings legacyMappings) {
            this.legacyMappings = Objects.requireNonNull(legacyMappings);
            return this;
        }

        NeoFormFixture build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one source must be added");
            }
            return new NeoFormFixture(
                    minecraftVersion,
                    javaVersion,
                    sources,
                    compileLauncherSources,
                    launcherSources,
                    minecraftLibraries,
                    neoFormLibraries,
                    legacyMappings
            );
        }
    }
}
