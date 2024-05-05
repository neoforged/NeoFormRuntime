package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.artifacts.ArtifactManager;
import net.neoforged.neoforminabox.downloads.DownloadManager;
import net.neoforged.neoforminabox.engine.NeoFormEngine;
import net.neoforged.neoforminabox.engine.ProcessingStepManager;
import picocli.CommandLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

public abstract class NeoFormEngineCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.Option(names = "--print-graph")
    boolean printGraph;

    protected abstract void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException;

    @Override
    public final Integer call() throws Exception {
        var start = System.currentTimeMillis();

        var closables = new ArrayList<AutoCloseable>();

        try (var lockManager = new LockManager(commonOptions.cacheDir);
             var cacheManager = new CacheManager(commonOptions.cacheDir);
             var downloadManager = new DownloadManager()) {
            var artifactManager = new ArtifactManager(commonOptions.repositories, cacheManager, downloadManager, lockManager, commonOptions.launcherManifestUrl);

            if (commonOptions.artifactManifest != null) {
                artifactManager.loadArtifactManifest(commonOptions.artifactManifest);
            }

            var processingStepManager = new ProcessingStepManager(commonOptions.cacheDir.resolve("work"), cacheManager, artifactManager);
            var fileHashService = new FileHashService();
            try (var engine = new NeoFormEngine(artifactManager, fileHashService, cacheManager, processingStepManager, lockManager)) {
                runWithNeoFormEngine(engine, closables);
            }
        } finally {
            for (var closable : closables) {
                try {
                    closable.close();
                } catch (Exception e) {
                    System.err.println("Failed to close " + closable + ": " + e);
                }
            }

            var elapsed = System.currentTimeMillis() - start;
            System.out.format(Locale.ROOT, "Total runtime: %.02fs\n", elapsed / 1000.0);
        }
        return 0;
    }
}
