package net.neoforged.neoform.runtime.cli;

import com.google.gson.Gson;
import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RunNeoFormCommandCacheKeyTest {
    private static final Gson GSON = new Gson();
    private static final List<String> ACTION_CACHE_KEY_NODE_IDS = List.of(
            "patch",
            "binaryPatch",
            "applyNeoforgePatches",
            "transformSources",
            "applyDevTransforms"
    );

    @Test
    void binaryPatchCacheKeyChangesWhenPatchDataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch-a", "access-transformer"),
                new UserdevFixture("binary-patch-b", "access-transformer")
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB, "binaryPatch");
    }

    @Test
    void sourcePatchCacheKeyChangesWhenPatchDataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer")
                        .withSourcePatchContent("source-patch-a"),
                new UserdevFixture("binary-patch", "access-transformer")
                        .withSourcePatchContent("source-patch-b")
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB, "applyNeoforgePatches");
    }

    @Test
    void accessTransformerCacheKeysChangeWhenAccessTransformerDataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer-a"),
                new UserdevFixture("binary-patch", "access-transformer-b")
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB, "transformSources", "applyDevTransforms");
    }

    @Test
    void cacheKeysDoNotChangeWhenUserdevContentsDoNotChange(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer"),
                new UserdevFixture("binary-patch", "access-transformer")
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB);
    }

    @Test
    void sourcePatchesDoNotAffectNoRecompileCacheKeys(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer")
                        .withExtraEntry("patches/net/minecraft/client/Minecraft.java.patch", "source-patch-a"),
                new UserdevFixture("binary-patch", "access-transformer")
                        .withExtraEntry("patches/net/minecraft/client/Minecraft.java.patch", "source-patch-b")
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB, "applyNeoforgePatches");
    }

    @Test
    void cacheKeysDoNotChangeWhenUserdevZipMetadataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer")
                        .withZipTimestamp(0),
                new UserdevFixture("binary-patch", "access-transformer")
                        .withZipTimestamp(1000)
                        .reverseZipEntries()
        );
        var engineA = buildEngineForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var engineB = buildEngineForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertOnlyActionCacheKeysChange(engineA, engineB);
    }

    @Test
    void neoFormPatchCacheKeyDoesNotChangeWhenNeoFormArchivePathChanges(@TempDir Path tempDir) throws Exception {
        var neoformA = tempDir.resolve("neoform-a.zip");
        var neoformB = tempDir.resolve("neoform-b.zip");
        writeNeoForm(neoformA, "source-patch");
        writeNeoForm(neoformB, "source-patch");

        var engineA = buildEngineForNeoFormFixture(tempDir.resolve("home-a"), neoformA);
        var engineB = buildEngineForNeoFormFixture(tempDir.resolve("home-b"), neoformB);
        var keyA = computeActionCacheKey(engineA, "patch");
        var keyB = computeActionCacheKey(engineB, "patch");

        assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        assertThat(keyA.components().get("command line arg").value())
                .isEqualTo(keyB.components().get("command line arg").value());
    }

    private static void assertOnlyActionCacheKeysChange(CapturedEngine engineA, CapturedEngine engineB, String... changedNodeIds) {
        var changedNodes = Set.of(changedNodeIds);
        for (var nodeId : ACTION_CACHE_KEY_NODE_IDS) {
            var keyA = computeActionCacheKey(engineA, nodeId);
            var keyB = computeActionCacheKey(engineB, nodeId);
            if (changedNodes.contains(nodeId)) {
                assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
            } else {
                assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
            }
        }
    }

    private static CacheKey computeActionCacheKey(CapturedEngine engine, String nodeId) {
        var cacheKey = engine.actionCacheKeys().get(nodeId);
        assertNotNull(cacheKey);
        return cacheKey;
    }

    private static CacheKey computeActionCacheKey(NeoFormEngine engine, String nodeId) {
        var builder = engine.createCacheKeyBuilder(nodeId);
        engine.getGraph().getRequiredNode(nodeId).action().computeCacheKey(builder);
        return builder.build();
    }

    private static CapturedEngine buildEngineForFixture(Path homeDir, Path userdev) {
        return buildEngine(
                "--home-dir", homeDir.toString(),
                "--disable-cache-maintenance",
                "--neoforge", userdev.toString()
        );
    }

    private static CapturedEngine buildEngineForNeoFormFixture(Path homeDir, Path neoform) {
        return buildEngine(
                "--home-dir", homeDir.toString(),
                "--disable-cache-maintenance",
                "--neoform", neoform.toString()
        );
    }

    private record NeoForgeCacheKeyFixture(Path userdevA, Path userdevB) {}

    private record CapturedEngine(Map<String, CacheKey> actionCacheKeys) {}

    private record UserdevFixture(String binaryPatchContent,
                                  String accessTransformerContent,
                                  String sourcePatchContent,
                                  Map<String, String> extraEntries,
                                  long zipTimestamp,
                                  boolean zipEntriesReversed) {
        private UserdevFixture(String binaryPatchContent, String accessTransformerContent) {
            this(binaryPatchContent, accessTransformerContent, "source-patch", Map.of(), 0, false);
        }

        private UserdevFixture withExtraEntry(String name, String content) {
            var extraEntries = new LinkedHashMap<>(this.extraEntries);
            extraEntries.put(name, content);
            return new UserdevFixture(binaryPatchContent, accessTransformerContent, sourcePatchContent, extraEntries, zipTimestamp, zipEntriesReversed);
        }

        private UserdevFixture withSourcePatchContent(String sourcePatchContent) {
            return new UserdevFixture(binaryPatchContent, accessTransformerContent, sourcePatchContent, extraEntries, zipTimestamp, zipEntriesReversed);
        }

        private UserdevFixture withZipTimestamp(long zipTimestamp) {
            return new UserdevFixture(binaryPatchContent, accessTransformerContent, sourcePatchContent, extraEntries, zipTimestamp, zipEntriesReversed);
        }

        private UserdevFixture reverseZipEntries() {
            return new UserdevFixture(binaryPatchContent, accessTransformerContent, sourcePatchContent, extraEntries, zipTimestamp, true);
        }
    }

    private static NeoForgeCacheKeyFixture createNeoForgeCacheKeyFixture(Path tempDir,
                                                                        UserdevFixture userdevFixtureA,
                                                                        UserdevFixture userdevFixtureB) throws IOException {
        var neoform = tempDir.resolve("neoform.zip");
        var sources = tempDir.resolve("neoforge-sources.jar");
        var universal = tempDir.resolve("neoforge-universal.jar");
        var userdevA = tempDir.resolve("neoforge-a-userdev.jar");
        var userdevB = tempDir.resolve("neoforge-b-userdev.jar");

        // Both userdev jars point at the same NeoForm config, sources, universal jar, and binpatcher args.
        // Only the selected embedded NeoForge data differs, so any key change must come from data dependencies.
        writeNeoForm(neoform, "source-patch");
        writeZip(sources, Map.of("net/neoforged/test/Example.java", "package net.neoforged.test;\nclass Example {}\n"));
        writeZip(universal, Map.of("net/neoforged/test/Example.class", "compiled"));
        writeNeoForgeUserdev(userdevA, neoform, sources, universal, userdevFixtureA);
        writeNeoForgeUserdev(userdevB, neoform, sources, universal, userdevFixtureB);

        return new NeoForgeCacheKeyFixture(userdevA, userdevB);
    }

    private static void writeNeoForm(Path neoform, String sourcePatchContent) throws IOException {
        writeZip(neoform, Map.of(
                "config.json", """
                        {
                          "spec": 1,
                          "version": "1.21",
                          "official": true,
                          "java_target": 21,
                          "encoding": "UTF-8",
                          "data": {
                            "patches": "patches/"
                          },
                          "steps": {
                            "joined": [
                              { "type": "downloadJson" },
                              { "type": "rename", "input": "{downloadJsonOutput}" },
                              { "type": "decompile", "input": "{renameOutput}" },
                              { "type": "patch", "input": "{decompileOutput}" }
                            ]
                          },
                          "functions": {
                            "rename": {
                              "version": "test:tool:1.0",
                              "args": ["{input}", "{output}"]
                            },
                            "decompile": {
                              "version": "test:tool:1.0",
                              "args": ["{input}", "{output}"]
                            }
                          },
                          "libraries": {
                            "joined": []
                          }
                        }
                        """,
                "patches/", "",
                "patches/.keep", "",
                "patches/net/neoforged/test.patch", sourcePatchContent
        ));
    }

    private static void writeNeoForgeUserdev(Path userdev,
                                             Path neoform,
                                             Path sources,
                                             Path universal,
                                             UserdevFixture fixture) throws IOException {
        // Keep the visible binary patch command identical across fixtures; the cache key must change
        // because of the {patch} data source contents, not because the command line changed.
        var entries = new LinkedHashMap<String, String>();
        entries.put("config.json", """
                        {
                          "spec": 2,
                          "mcp": %s,
                          "ats": "ats/",
                          "binpatches": "binary/patches.lzma",
                          "binpatcher": {
                            "version": "test:binpatcher:1.0",
                            "args": ["--patch", "--base", "{clean}", "--output", "{output}", "--patches", "{patch}"]
                          },
                          "patches": "patches/",
                          "sources": %s,
                          "universal": %s,
                          "patchesOriginalPrefix": "a/",
                          "patchesModifiedPrefix": "b/",
                          "runs": {},
                          "libraries": [],
                          "modules": [],
                          "sass": []
                        }
                        """.formatted(jsonString(neoform), jsonString(sources), jsonString(universal)));
        entries.put("ats/", "");
        entries.put("ats/accesstransformer.cfg", fixture.accessTransformerContent());
        entries.put("binary/patches.lzma", fixture.binaryPatchContent());
        entries.put("patches/", "");
        entries.put("patches/.keep", "");
        entries.put("patches/net/neoforged/test.patch", fixture.sourcePatchContent());
        entries.putAll(fixture.extraEntries());
        writeZip(userdev, entries, fixture.zipTimestamp(), fixture.zipEntriesReversed());
    }

    private static String jsonString(Path path) {
        return GSON.toJson(path.toString());
    }

    private static void writeZip(Path path, Map<String, String> entries) throws IOException {
        writeZip(path, entries, 0, false);
    }

    private static void writeZip(Path path, Map<String, String> entries, long timestamp, boolean reverseEntries) throws IOException {
        var zipEntries = new ArrayList<>(entries.entrySet());
        if (reverseEntries) {
            Collections.reverse(zipEntries);
        }

        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : zipEntries) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(timestamp);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static CapturedEngine buildEngine(String... args) {
        var fullArgs = new ArrayList<String>();
        // The cache-key wiring exists after graph construction; executing nodes would require fake tools to run.
        Collections.addAll(fullArgs, "run", "--print-graph");
        Collections.addAll(fullArgs, args);

        var engineHolder = new AtomicReference<CapturedEngine>();
        var commandLine = new CommandLine(new Main(), new EngineCapturingCommandFactory(engineHolder));
        assertEquals(0, commandLine.execute(fullArgs.toArray(String[]::new)));

        var engine = engineHolder.get();
        assertNotNull(engine);
        return engine;
    }

    private static final class EngineCapturingCommandFactory implements CommandLine.IFactory {
        private final AtomicReference<CapturedEngine> engineHolder;
        private final CommandLine.IFactory defaultFactory = CommandLine.defaultFactory();

        private EngineCapturingCommandFactory(AtomicReference<CapturedEngine> engineHolder) {
            this.engineHolder = engineHolder;
        }

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            if (cls == RunNeoFormCommand.class) {
                return cls.cast(new EngineCapturingRunNeoFormCommand(engineHolder));
            }
            return defaultFactory.create(cls);
        }
    }

    private static final class EngineCapturingRunNeoFormCommand extends RunNeoFormCommand {
        private final AtomicReference<CapturedEngine> engineHolder;

        private EngineCapturingRunNeoFormCommand(AtomicReference<CapturedEngine> engineHolder) {
            this.engineHolder = engineHolder;
        }

        @Override
        protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
            super.runWithNeoFormEngine(engine, closables);
            var actionCacheKeys = new LinkedHashMap<String, CacheKey>();
            for (var nodeId : ACTION_CACHE_KEY_NODE_IDS) {
                if (engine.getGraph().getNode(nodeId) != null) {
                    actionCacheKeys.put(nodeId, computeActionCacheKey(engine, nodeId));
                }
            }
            engineHolder.set(new CapturedEngine(Map.copyOf(actionCacheKeys)));
        }
    }
}
