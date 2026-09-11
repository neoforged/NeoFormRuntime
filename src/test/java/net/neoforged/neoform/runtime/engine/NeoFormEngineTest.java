package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.cli.LockManager;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NeoFormEngineTest {
    private static final String NODE_ID = "cacheUseNode";

    @TempDir
    Path tempDir;

    @Test
    void engineKeepsCacheEntryAndBlocksMaintenance() throws Exception {
        // Set up temp-backed managers so this test covers the on-disk cache entry
        // and the file locks used between NFRT processes.
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
            var node = newNode(engine);
            engine.getGraph().setResult("testResult", node.getRequiredOutput("output"));
            buildOutput = tempDir.resolve("project-build").resolve("output.txt");
            Files.createDirectories(buildOutput.getParent());

            // Write results through the engine.
            // The special test cache manager will attempt maintenance after the cache entry is
            // saved but before this call copies the cache path into the build directory.
            engine.writeResults(Map.of("testResult", buildOutput));
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

    private static NeoFormEngine newEngine(CacheManager cacheManager, LockManager lockManager) {
        return new NeoFormEngine(
                mock(ArtifactManager.class),
                new FileHashService(),
                cacheManager,
                lockManager
        );
    }

    private static ExecutionNode newNode(NeoFormEngine engine) {
        var builder = engine.getGraph().nodeBuilder(NODE_ID);
        builder.output("output", NodeOutputType.TXT, "Test output");
        builder.action(new CacheableTestAction());
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

    private static final class CacheableTestAction implements ExecutionNodeAction {
        @Override
        public void run(ProcessingEnvironment environment) throws IOException {
            Files.writeString(environment.getOutputPath("output"), "cached output");
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

            // Simulate a race condition: Make the just-published entry eligible for cleanup,
            // then run maintenance before writeResults can copy the returned cache path.
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
}
