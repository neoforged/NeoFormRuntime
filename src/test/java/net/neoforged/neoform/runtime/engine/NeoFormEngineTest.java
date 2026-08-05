package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.cli.LockManager;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.graph.NodeInput;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

class NeoFormEngineTest {
    private static final String NODE_ID = "cacheUseNode";
    private static final String CACHE_HIT_NODE_ID = "cacheHitNode";
    private static final String TEST_RESULT_ID = "testResult";
    private static final String OLDER_MINECRAFT_VERSION = "26.1";
    private static final String NEWER_MINECRAFT_VERSION = "26.2";

    @TempDir
    Path tempDir;

    @Test
    void engineKeepsCacheEntryAndBlocksMaintenance() throws Exception {
        // Set up temp-backed managers so this test covers the on-disk cache
        // entry and the file locks used between NFRT processes.
        var homeDir = tempDir.resolve("cache");
        var cacheManager = new MaintenanceDuringSaveCacheManager(
                homeDir,
                tempDir.resolve("assets"),
                tempDir.resolve("workspaces")
        );
        var lockManager = new LockManager(tempDir.resolve("locks"));

        Path cachedOutput;
        Path buildOutput;
        try (var engine = newEngine(cacheManager, lockManager)) {
            // Set up a result backed by a cacheable node so writeResults must
            // run the node, save it, and copy the cached path.
            var node = newNode(engine);
            engine.getGraph().setResult(TEST_RESULT_ID, node.getRequiredOutput("output"));
            buildOutput = tempDir.resolve("project-build").resolve("output.txt");
            Files.createDirectories(buildOutput.getParent());

            // Write results through the engine. The special cache manager
            // attempts maintenance after the cache entry is saved but before
            // this call copies the cache path into the build directory.
            engine.writeResults(Map.of(TEST_RESULT_ID, buildOutput));
            cachedOutput = node.getRequiredOutput("output").getResultPath();

            // Verify maintenance tried to run during the engine operation and
            // did not delete the cache-owned path before writeResults copied it.
            assertThat(cacheManager.maintenanceAttempted()).isTrue();
            assertThat(cacheManager.cacheOutputSurvivedMaintenance()).isTrue();
            assertThat(cachedOutput).hasContent("cached output");
            assertThat(buildOutput).hasContent("cached output");

            // Run maintenance again after writeResults releases the use lock,
            // but before the engine closes.
            cacheManager.performMaintenance();

            // Verify the cache entry is allowed to disappear after writeResults
            // is done, while the copied project output remains intact.
            assertThat(cachedOutput).doesNotExist();
            assertThat(buildOutput).hasContent("cached output");
        }
    }

    @Test
    void writeResultsExplainsMissingCachePath() throws Exception {
        // Set up one cache entry using the normal cache manager, so the second
        // engine run can restore the node output from the intermediate cache.
        var homeDir = tempDir.resolve("cache");
        var lockManager = new LockManager(tempDir.resolve("locks"));
        try (var engine = newEngine(newCacheManager(homeDir), lockManager)) {
            var node = newNode(engine);
            writeNodeResult(engine, node, tempDir.resolve("seed-output.txt"));
        }

        // Set up a cache manager that simulates a non-cooperating process, such
        // as an older NFRT version, deleting the restored cache path.
        var deletingCacheManager = new DeletingRestoredOutputCacheManager(
                homeDir,
                tempDir.resolve("assets"),
                tempDir.resolve("workspaces")
        );

        try (var engine = newEngine(deletingCacheManager, lockManager)) {
            // Create a result backed by the cached node so writeResults consumes
            // the restored cache path while the engine is still open.
            var node = newNode(engine);
            var destination = tempDir.resolve("project-build").resolve("output.txt");

            // Verify the error says the output path disappeared and names the
            // likely non-cooperating cleanup scenario.
            assertThatThrownBy(() -> writeNodeResult(engine, node, destination))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Result '" + TEST_RESULT_ID + "' could not be written")
                    .hasMessageContaining("output path is missing")
                    .hasMessageContaining("older NeoFormRuntime version or manual deletion");
        }
    }

    @Test
    void cacheHitBypassesHeldLock() throws Exception {
        // Set up shared managers to match two Gradle/NFRT invocations using the
        // same intermediate cache and node-lock directory.
        var cacheManager = new CacheManager(
                tempDir.resolve("cache"),
                tempDir.resolve("assets"),
                tempDir.resolve("workspaces")
        );
        var lockManager = new LockManager(tempDir.resolve("locks"));

        try (var engine = newEngine(cacheManager, lockManager)) {
            // Set up an older Minecraft-version node first, matching an IDE
            // session that previously synced a different target version.
            var node = newVersionedNode(engine, OLDER_MINECRAFT_VERSION, false);

            // Run normally so NFRT writes a complete older-version cache entry
            // through the standard writeResults path.
            var buildOutput = tempDir.resolve("older-version").resolve("output.txt");
            writeNodeResult(engine, node, buildOutput);

            // Verify the old-version output was produced for that cache key.
            assertThat(buildOutput)
                    .hasContent("cached output for " + OLDER_MINECRAFT_VERSION);
        }

        CacheKey cacheKey;
        Path cachedOutput;
        try (var engine = newEngine(cacheManager, lockManager)) {
            // Set up the new-version node and compute its cache key before
            // running so the test can hold the exact lock writeResults would use.
            var node = newVersionedNode(engine, NEWER_MINECRAFT_VERSION, false);
            cacheKey = cacheKeyFor(engine, node);

            // Run normally once so the new version also has a valid cache entry
            // that a later Gradle invocation should be able to reuse.
            var buildOutput = tempDir.resolve("new-version").resolve("output.txt");
            cachedOutput = writeNodeResult(engine, node, buildOutput);

            // Verify the new cache entry contains the new-version output instead
            // of accidentally reusing the old-version entry.
            assertThat(cachedOutput)
                    .hasContent("cached output for " + NEWER_MINECRAFT_VERSION);
            assertThat(buildOutput)
                    .hasContent("cached output for " + NEWER_MINECRAFT_VERSION);
        }

        var capturedOutput = new ByteArrayOutputStream();
        var capturingOut = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        CompletableFuture<Void> future = null;

        // Set up another process holding the 26.2 node lock while this process
        // already has a complete cache hit available for the same key.
        LockManager.Lock heldLock = lockManager.lock(cacheKey.toString());
        try {
            try (var engine = newEngine(cacheManager, lockManager)) {
                // Set up the same 26.2 node with an action that fails if the
                // cache hit path accidentally runs the node body.
                var node = newVersionedNode(engine, NEWER_MINECRAFT_VERSION, true);
                var buildOutput = tempDir.resolve("cache-hit").resolve("output.txt");

                // Write the result and verify it finishes before LockManager's
                // 1s polling interval, proving the hit bypassed the held lock.
                assertTimeoutPreemptively(
                        Duration.ofMillis(750),
                        () -> writeNodeResult(engine, node, buildOutput)
                );

                // Verify the written result came from the existing 26.2 cache
                // entry, not from executing the node action again.
                assertThat(buildOutput)
                        .hasContent("cached output for " + NEWER_MINECRAFT_VERSION);
            }

            // Remove only the cached output file while leaving the marker file in
            // place. This keeps the cache key the same but forces a cache miss.
            Files.delete(cachedOutput);

            try (var engine = newEngine(cacheManager, lockManager)) {
                // Set up another equivalent 26.2 node whose pre-lock restore now
                // misses and must use the same node lock as the computed key.
                var blockedNode = newVersionedNode(engine, NEWER_MINECRAFT_VERSION, false);
                var buildOutput = tempDir.resolve("cache-miss").resolve("output.txt");

                // Redirect output before the async run so the lock wait message
                // can prove writeResults is blocked on the expected cache key.
                System.setOut(capturingOut);

                // Run on another thread because the expected behavior is to wait
                // on the held lock until this test releases it.
                future = CompletableFuture.runAsync(() -> writeNodeResultUnchecked(engine, blockedNode, buildOutput));
                waitUntil(
                        () -> output(capturedOutput).contains("Waiting for lock on " + cacheKey),
                        future,
                        Duration.ofSeconds(2)
                );

                // Release the lock so the blocked operation can complete through
                // the normal miss path after proving which key blocked it.
                heldLock.close();
                heldLock = null;

                // Wait for completion so the final assertion observes the rerun
                // after the deliberately invalidated cache entry.
                future.get(3, TimeUnit.SECONDS);

                // Verify the blocked operation regenerated the 26.2 output after
                // the lock was released.
                assertThat(buildOutput)
                        .hasContent("cached output for " + NEWER_MINECRAFT_VERSION);
            }
        } finally {
            if (heldLock != null) {
                heldLock.close();
            }
            if (future != null) {
                future.cancel(true);
            }
            System.setOut(originalOut);
            capturingOut.close();
        }
    }

    private static NeoFormEngine newEngine(CacheManager cacheManager, LockManager lockManager) {
        return new NeoFormEngine(
                mock(ArtifactManager.class),
                new FileHashService(),
                cacheManager,
                lockManager
        );
    }

    private CacheManager newCacheManager(Path homeDir) throws IOException {
        return new CacheManager(
                homeDir,
                tempDir.resolve("assets"),
                tempDir.resolve("workspaces")
        );
    }

    private static ExecutionNode newNode(NeoFormEngine engine) {
        var builder = engine.getGraph().nodeBuilder(NODE_ID);
        builder.output("output", NodeOutputType.TXT, "Test output");
        builder.action(new CacheableTestAction());
        return builder.build();
    }

    private static ExecutionNode newVersionedNode(NeoFormEngine engine,
                                                  String minecraftVersion,
                                                  boolean failIfRun) {
        var builder = engine.getGraph().nodeBuilder(CACHE_HIT_NODE_ID);
        builder.input("minecraftVersion", new MinecraftVersionInput(minecraftVersion));
        builder.output("output", NodeOutputType.TXT, "Test output");
        builder.action(new VersionedTestAction(minecraftVersion, failIfRun));
        return builder.build();
    }

    private static Path writeNodeResult(NeoFormEngine engine,
                                        ExecutionNode node,
                                        Path destination) throws IOException, InterruptedException {
        var parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        engine.getGraph().setResult(TEST_RESULT_ID, node.getRequiredOutput("output"));
        engine.writeResults(Map.of(TEST_RESULT_ID, destination));
        return node.getRequiredOutput("output").getResultPath();
    }

    private static void writeNodeResultUnchecked(NeoFormEngine engine,
                                                 ExecutionNode node,
                                                 Path destination) {
        try {
            writeNodeResult(engine, node, destination);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static CacheKey cacheKeyFor(NeoFormEngine engine, ExecutionNode node) {
        var builder = engine.createCacheKeyBuilder(node.id());
        for (var input : node.inputs().values()) {
            input.collectCacheKeyComponent(builder);
        }
        node.action().computeCacheKey(builder);
        return builder.build();
    }

    private static void makePeriodicMaintenanceDue(Path homeDir) throws IOException {
        var maintenanceState = homeDir.resolve("nfrt_cache_cleanup.state");
        if (Files.notExists(maintenanceState)) {
            Files.createFile(maintenanceState);
        }
        Files.setLastModifiedTime(
                maintenanceState,
                FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS))
        );
    }

    private static void waitUntil(BooleanSupplier condition,
                                  CompletableFuture<?> future,
                                  Duration timeout) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (future.isDone()) {
                future.get(0, TimeUnit.SECONDS);
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for condition");
            }
            Thread.sleep(10);
        }
    }

    private static String output(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class MinecraftVersionInput extends NodeInput {
        private final String minecraftVersion;

        MinecraftVersionInput(String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
        }

        @Override
        public void replaceReferences(NodeOutput oldOutput, NodeOutput newOutput) {
        }

        @Override
        public Collection<ExecutionNode> getNodeDependencies() {
            return List.of();
        }

        @Override
        public void collectCacheKeyComponent(CacheKeyBuilder builder) {
            builder.add(getId(), minecraftVersion);
        }

        @Override
        public <T> T getValue(ResultRepresentation<T> representation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NodeInput copy() {
            return new MinecraftVersionInput(minecraftVersion);
        }
    }

    private static final class CacheableTestAction implements ExecutionNodeAction {
        @Override
        public void run(ProcessingEnvironment environment) throws IOException {
            Files.writeString(environment.getOutputPath("output"), "cached output");
        }
    }

    private static final class VersionedTestAction implements ExecutionNodeAction {
        private final String minecraftVersion;
        private final boolean failIfRun;

        VersionedTestAction(String minecraftVersion, boolean failIfRun) {
            this.minecraftVersion = minecraftVersion;
            this.failIfRun = failIfRun;
        }

        @Override
        public void run(ProcessingEnvironment environment) throws IOException {
            if (failIfRun) {
                throw new AssertionError("Node action should not run on a cache hit");
            }
            Files.writeString(environment.getOutputPath("output"), "cached output for " + minecraftVersion);
        }
    }

    private static final class MaintenanceDuringSaveCacheManager extends CacheManager {
        private final Path homeDir;
        private boolean maintenanceAttempted;
        private boolean cacheOutputSurvivedMaintenance;

        private MaintenanceDuringSaveCacheManager(Path homeDir,
                                                  Path assetsDir,
                                                  Path workspacesDir) throws IOException {
            super(homeDir, assetsDir, workspacesDir);
            this.homeDir = homeDir;
        }

        @Override
        public void saveOutputs(ExecutionNode node,
                                CacheKey cacheKey,
                                HashMap<String, Path> outputValues) throws IOException {
            super.saveOutputs(node, cacheKey, outputValues);
            var cachedOutput = outputValues.get("output");

            // Make the just-published entry eligible for cleanup, then run
            // maintenance before writeResults copies the returned cache path.
            var marker = homeDir.resolve("intermediate_results").resolve(cacheKey + ".txt");
            Files.setLastModifiedTime(
                    marker,
                    FileTime.from(Instant.now().minus(32, ChronoUnit.DAYS))
            );
            makePeriodicMaintenanceDue(homeDir);
            maintenanceAttempted = true;
            performMaintenance();
            cacheOutputSurvivedMaintenance = Files.isRegularFile(cachedOutput);
        }

        private boolean maintenanceAttempted() {
            return maintenanceAttempted;
        }

        private boolean cacheOutputSurvivedMaintenance() {
            return cacheOutputSurvivedMaintenance;
        }
    }

    private static final class DeletingRestoredOutputCacheManager extends CacheManager {
        private DeletingRestoredOutputCacheManager(Path homeDir,
                                                  Path assetsDir,
                                                  Path workspacesDir) throws IOException {
            super(homeDir, assetsDir, workspacesDir);
        }

        @Override
        public boolean restoreOutputsFromCache(ExecutionNode node,
                                               CacheKey cacheKey,
                                               Map<String, Path> outputValues) throws IOException {
            return deleteRestoredOutputs(
                    super.restoreOutputsFromCache(node, cacheKey, outputValues),
                    outputValues
            );
        }

        @Override
        public boolean restoreOutputsFromCacheWithoutMissAnalysis(ExecutionNode node,
                                                                  CacheKey cacheKey,
                                                                  Map<String, Path> outputValues) throws IOException {
            return deleteRestoredOutputs(
                    super.restoreOutputsFromCacheWithoutMissAnalysis(node, cacheKey, outputValues),
                    outputValues
            );
        }

        private boolean deleteRestoredOutputs(boolean restored,
                                              Map<String, Path> outputValues) throws IOException {
            if (restored) {
                for (var output : outputValues.values()) {
                    Files.delete(output);
                }
            }
            return restored;
        }
    }
}
