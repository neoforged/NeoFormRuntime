package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.config.neoforge.NeoForgeConfig;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(name = "neoform", mixinStandardHelpOptions = true)
public class Main implements Callable<Integer> {
    @Option(names = "--cache-dir")
    Path cacheDir = Paths.get(System.getProperty("user.home")).resolve(".neoform");

    @Option(names = "--repository", arity = "*")
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"));

    @Option(names = "--launcher-meta-uri")
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    SourceArtifacts sourceArtifacts;

    @Option(names = "--dist", required = true)
    String dist;

    static class SourceArtifacts {
        @Option(names = "--neoform")
        String neoform;
        @Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    public Integer call() throws Exception {
        try (var lockManager = new LockManager(cacheDir);
             var cacheManager = new CacheManager(cacheDir);
             var downloadManager = new DownloadManager()) {
            var artifactManager = new ArtifactManager(repositories, cacheManager, downloadManager, lockManager, launcherManifestUrl);
            var processingStepManager = new ProcessingStepManager(cacheDir.resolve("work"), cacheManager, artifactManager);

            String neoformArtifact;
            if (sourceArtifacts.neoforge != null) {
                var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
                var neoforgeConfig = NeoForgeConfig.from(neoforgeArtifact.path());
                throw new UnsupportedOperationException(); // NYI
            } else {
                neoformArtifact = sourceArtifacts.neoform;
            }

            try (var neoFormEngine = NeoFormEngine.create(artifactManager, processingStepManager, neoformArtifact, dist)) {
                neoFormEngine.run();
            }

        }
        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
