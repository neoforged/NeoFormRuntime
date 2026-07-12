package net.neoforged.neoform.runtime.cli;

import com.google.gson.Gson;
import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RunNeoFormCommandCacheKeyTest {
    private static final Gson GSON = new Gson();

    @Test
    void binaryPatchCacheKeyChangesWhenPatchDataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch-a", "access-transformer"),
                new UserdevFixture("binary-patch-b", "access-transformer")
        );
        var graphA = buildGraphForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var graphB = buildGraphForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertActionCacheKeyChanges(graphA, graphB, "binaryPatch");
    }

    @Test
    void accessTransformerCacheKeysChangeWhenAccessTransformerDataChanges(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer-a"),
                new UserdevFixture("binary-patch", "access-transformer-b")
        );
        var graphA = buildGraphForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var graphB = buildGraphForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertActionCacheKeyChanges(graphA, graphB, "transformSources");
        assertActionCacheKeyChanges(graphA, graphB, "applyDevTransforms");
    }

    @Test
    void cacheKeysDoNotChangeWhenUserdevContentsDoNotChange(@TempDir Path tempDir) throws Exception {
        var fixture = createNeoForgeCacheKeyFixture(
                tempDir,
                new UserdevFixture("binary-patch", "access-transformer"),
                new UserdevFixture("binary-patch", "access-transformer")
        );
        var graphA = buildGraphForFixture(tempDir.resolve("home-a"), fixture.userdevA());
        var graphB = buildGraphForFixture(tempDir.resolve("home-b"), fixture.userdevB());

        assertActionCacheKeyDoesNotChange(graphA, graphB, "binaryPatch");
        assertActionCacheKeyDoesNotChange(graphA, graphB, "transformSources");
        assertActionCacheKeyDoesNotChange(graphA, graphB, "applyDevTransforms");
    }

    private static void assertActionCacheKeyChanges(ExecutionGraph graphA, ExecutionGraph graphB, String nodeId) {
        var keyA = computeActionCacheKey(graphA, nodeId);
        var keyB = computeActionCacheKey(graphB, nodeId);

        assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
    }

    private static void assertActionCacheKeyDoesNotChange(ExecutionGraph graphA, ExecutionGraph graphB, String nodeId) {
        var keyA = computeActionCacheKey(graphA, nodeId);
        var keyB = computeActionCacheKey(graphB, nodeId);

        assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
    }

    private static CacheKey computeActionCacheKey(ExecutionGraph graph, String nodeId) {
        var builder = new CacheKeyBuilder(nodeId, new FileHashService());
        graph.getRequiredNode(nodeId).action().computeCacheKey(builder);
        return builder.build();
    }

    private static ExecutionGraph buildGraphForFixture(Path homeDir, Path userdev) {
        return buildGraph(
                "--home-dir", homeDir.toString(),
                "--disable-cache-maintenance",
                "--neoforge", userdev.toString()
        );
    }

    private record NeoForgeCacheKeyFixture(Path userdevA, Path userdevB) {}

    private record UserdevFixture(String binaryPatchContent, String accessTransformerContent) {}

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
                "patches/.keep", ""
        ));
        writeZip(sources, Map.of("net/neoforged/test/Example.java", "package net.neoforged.test;\nclass Example {}\n"));
        writeZip(universal, Map.of("net/neoforged/test/Example.class", "compiled"));
        writeNeoForgeUserdev(userdevA, neoform, sources, universal, userdevFixtureA);
        writeNeoForgeUserdev(userdevB, neoform, sources, universal, userdevFixtureB);

        return new NeoForgeCacheKeyFixture(userdevA, userdevB);
    }

    private static void writeNeoForgeUserdev(Path userdev,
                                             Path neoform,
                                             Path sources,
                                             Path universal,
                                             UserdevFixture fixture) throws IOException {
        // Keep the visible binary patch command identical across fixtures; the cache key must change
        // because of the {patch} data source contents, not because the command line changed.
        writeZip(userdev, Map.of(
                "config.json", """
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
                        """.formatted(jsonString(neoform), jsonString(sources), jsonString(universal)),
                "ats/accesstransformer.cfg", fixture.accessTransformerContent(),
                "binary/patches.lzma", fixture.binaryPatchContent(),
                "patches/.keep", ""
        ));
    }

    private static String jsonString(Path path) {
        return GSON.toJson(path.toString());
    }

    private static void writeZip(Path path, Map<String, String> entries) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static ExecutionGraph buildGraph(String... args) {
        var fullArgs = new ArrayList<String>();
        // The cache-key wiring exists after graph construction; executing nodes would require fake tools to run.
        Collections.addAll(fullArgs, "run", "--print-graph");
        Collections.addAll(fullArgs, args);

        var graphHolder = new AtomicReference<ExecutionGraph>();
        var commandLine = new CommandLine(new Main(), new GraphCapturingCommandFactory(graphHolder));
        assertEquals(0, commandLine.execute(fullArgs.toArray(String[]::new)));

        var graph = graphHolder.get();
        assertNotNull(graph);
        return graph;
    }

    private static final class GraphCapturingCommandFactory implements CommandLine.IFactory {
        private final AtomicReference<ExecutionGraph> graphHolder;
        private final CommandLine.IFactory defaultFactory = CommandLine.defaultFactory();

        private GraphCapturingCommandFactory(AtomicReference<ExecutionGraph> graphHolder) {
            this.graphHolder = graphHolder;
        }

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            if (cls == RunNeoFormCommand.class) {
                return cls.cast(new GraphCapturingRunNeoFormCommand(graphHolder));
            }
            return defaultFactory.create(cls);
        }
    }

    private static final class GraphCapturingRunNeoFormCommand extends RunNeoFormCommand {
        private final AtomicReference<ExecutionGraph> graphHolder;

        private GraphCapturingRunNeoFormCommand(AtomicReference<ExecutionGraph> graphHolder) {
            this.graphHolder = graphHolder;
        }

        @Override
        protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
            super.runWithNeoFormEngine(engine, closables);
            graphHolder.set(engine.getGraph());
        }
    }
}
