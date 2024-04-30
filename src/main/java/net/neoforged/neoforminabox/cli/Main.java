package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.actions.RecompileSourcesActionWithECJ;
import net.neoforged.neoforminabox.actions.RecompileSourcesActionWithJDK;
import net.neoforged.neoforminabox.config.neoforge.NeoForgeConfig;
import net.neoforged.neoforminabox.graph.NodeOutputType;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
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

    @Option(names = "--print-graph")
    boolean printGraph;

    @Option(names = "--recompile")
    boolean recompile;

    @Option(names = "--recompile-ecj")
    boolean recompileEcj;

    static class SourceArtifacts {
        @Option(names = "--neoform")
        String neoform;
        @Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    public Integer call() throws Exception {
        var start = System.currentTimeMillis();

        try (var lockManager = new LockManager(cacheDir);
             var cacheManager = new CacheManager(cacheDir);
             var downloadManager = new DownloadManager()) {
            var artifactManager = new ArtifactManager(repositories, cacheManager, downloadManager, lockManager, launcherManifestUrl);
            var processingStepManager = new ProcessingStepManager(cacheDir.resolve("work"), cacheManager, artifactManager);
            var fileHashService = new FileHashService();

            String neoformArtifact;
            if (sourceArtifacts.neoforge != null) {
                var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
                var neoforgeConfig = NeoForgeConfig.from(neoforgeArtifact.path());
                throw new UnsupportedOperationException(); // NYI
            } else {
                neoformArtifact = sourceArtifacts.neoform;
            }

            try (var neoFormEngine = NeoFormEngine.create(artifactManager, fileHashService, cacheManager, processingStepManager, lockManager, neoformArtifact, dist)) {
                var graph = neoFormEngine.buildGraph();

                if (printGraph) {
                    graph.dump(new PrintWriter(System.out));
                }

                // Patch is pretty much the last task in the NeoForm steps list
                var patchOutput = graph.getRequiredOutput("patch", "output");
                if (recompile || recompileEcj) {
                    var builder = graph.nodeBuilder("recompile");
                    builder.input("sources", patchOutput.asInput());
                    builder.inputFromNodeOutput("libraries", "listLibraries", "output");
                    builder.output("output", NodeOutputType.JAR, "Compiled minecraft sources");
                    if (recompileEcj) {
                        builder.action(new RecompileSourcesActionWithECJ());
                    } else {
                        builder.action(new RecompileSourcesActionWithJDK());
                    }
                    var recompileNode = builder.build();
                    neoFormEngine.runNode(recompileNode);
                } else {
                    neoFormEngine.runNode(patchOutput.node());
                }
            }
        } finally {
            var elapsed = System.currentTimeMillis() - start;
            System.out.format(Locale.ROOT, "Total runtime: %.02fs\n", elapsed / 1000.0);
        }

        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
