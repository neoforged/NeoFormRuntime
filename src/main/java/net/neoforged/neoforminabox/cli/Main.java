package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.actions.ActionWithClasspath;
import net.neoforged.neoforminabox.actions.ApplySourceAccessTransformersAction;
import net.neoforged.neoforminabox.actions.InjectFromZipFileSource;
import net.neoforged.neoforminabox.actions.InjectZipContentAction;
import net.neoforged.neoforminabox.actions.PatchActionFactory;
import net.neoforged.neoforminabox.artifacts.ArtifactManager;
import net.neoforged.neoforminabox.artifacts.ClasspathItem;
import net.neoforged.neoforminabox.config.neoforge.NeoForgeConfig;
import net.neoforged.neoforminabox.downloads.DownloadManager;
import net.neoforged.neoforminabox.engine.NeoFormEngine;
import net.neoforged.neoforminabox.engine.ProcessingStepManager;
import net.neoforged.neoforminabox.graph.NodeOutputType;
import net.neoforged.neoforminabox.graph.transforms.GraphTransform;
import net.neoforged.neoforminabox.graph.transforms.ModifyAction;
import net.neoforged.neoforminabox.graph.transforms.ReplaceNodeOutput;
import net.neoforged.neoforminabox.utils.FileUtil;
import net.neoforged.neoforminabox.utils.MavenCoordinate;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

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

    @Option(names = "--use-eclipse-compiler")
    boolean useEclipseCompiler;

    @Option(names = "--artifact-manifest")
    Path artifactManifest;

    @Option(names = "--write-result", arity = "*")
    List<String> writeResults = new ArrayList<>();

    static class SourceArtifacts {
        @Option(names = "--neoform")
        String neoform;
        @Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    public Integer call() throws Exception {
        var start = System.currentTimeMillis();

        var closables = new ArrayList<AutoCloseable>();

        try (var lockManager = new LockManager(cacheDir);
             var cacheManager = new CacheManager(cacheDir);
             var downloadManager = new DownloadManager()) {
            var artifactManager = new ArtifactManager(repositories, cacheManager, downloadManager, lockManager, launcherManifestUrl);

            if (artifactManifest != null) {
                artifactManager.loadArtifactManifest(artifactManifest);
            }

            var processingStepManager = new ProcessingStepManager(cacheDir.resolve("work"), cacheManager, artifactManager);
            var fileHashService = new FileHashService();
            try (var engine = new NeoFormEngine(artifactManager, fileHashService, cacheManager, processingStepManager, lockManager)) {
                engine.setUseEclipseCompiler(useEclipseCompiler);

                List<GraphTransform> transforms = new ArrayList<>();
                if (sourceArtifacts.neoforge != null) {
                    var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
                    try (var neoforgeZipFile = new JarFile(neoforgeArtifact.path().toFile())) {
                        var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);
                        MavenCoordinate neoformArtifact = MavenCoordinate.parse(neoforgeConfig.neoformArtifact());
                        // Allow it to be overridden
                        if (sourceArtifacts.neoform != null) {
                            System.out.println("Overriding NeoForm version " + neoformArtifact + " with CLI argument " + sourceArtifacts.neoform);
                            neoformArtifact = MavenCoordinate.parse(sourceArtifacts.neoform);
                        }

                        engine.loadNeoFormData(neoformArtifact, dist);

                        // Add NeoForge specific data sources
                        engine.addDataSource("neoForgeAccessTransformers", neoforgeZipFile, neoforgeConfig.accessTransformersFolder());

                        // Build the graph transformations needed to apply NeoForge to the NeoForm execution

                        // Add NeoForge libraries to the list of libraries
                        transforms.add(new ModifyAction<>(
                                "recompile",
                                ActionWithClasspath.class,
                                action -> {
                                    for (var mavenLibrary : neoforgeConfig.libraries()) {
                                        action.getClasspath().add(ClasspathItem.of(mavenLibrary));
                                    }
                                }
                        ));

                        // Also inject NeoForge sources, which we can get from the sources file
                        var neoforgeSources = artifactManager.get(neoforgeConfig.sourcesArtifact()).path();
                        var neoforgeSourcesZip = new ZipFile(neoforgeSources.toFile());
                        closables.add(neoforgeSourcesZip);

                        transforms.add(new ReplaceNodeOutput(
                                "patch",
                                "output",
                                "transformSources",
                                (builder, previousNodeOutput) -> {
                                    builder.input("input", previousNodeOutput.asInput());
                                    builder.inputFromNodeOutput("libraries", "listLibraries", "output");
                                    builder.action(new ApplySourceAccessTransformersAction("neoForgeAccessTransformers"));
                                    return builder.output("output", NodeOutputType.ZIP, "Sources with additional transforms (ATs, Parchment) applied");
                                }
                        ));

                        transforms.add(new ModifyAction<>(
                                "inject",
                                InjectZipContentAction.class,
                                action -> {
                                    action.getInjectedSources().add(
                                            new InjectFromZipFileSource(neoforgeSourcesZip, "/")
                                    );
                                }
                        ));

                        // Append a patch step to the NeoForge patches
                        transforms.add(new ReplaceNodeOutput("patch", "output", "applyNeoforgePatches",
                                (builder, previousOutput) -> {
                                    return PatchActionFactory.makeAction(builder, neoforgeArtifact.path(), neoforgeConfig.patchesFolder(), previousOutput);
                                }
                        ));

                        engine.applyTransforms(transforms);

                        if (printGraph) {
                            engine.dumpGraph(new PrintWriter(System.out));
                        }

                        var neededResults = writeResults.stream().map(encodedResult -> {
                                    var parts = encodedResult.split(":", 2);
                                    if (parts.length != 2) {
                                        throw new IllegalArgumentException("Specify a result destination in the form: <resultid>:<destination>");
                                    }
                                    return parts;
                                })
                                .collect(Collectors.toMap(
                                        parts -> parts[0],
                                        parts -> Paths.get(parts[1])
                                ));

                        if (neededResults.isEmpty()) {
                            System.err.println("No results requested. Available results: " + engine.getAvailableResults());
                            System.exit(1);
                        }

                        var results = engine.createResults(neededResults.keySet().toArray(new String[0]));

                        for (var entry : neededResults.entrySet()) {
                            var result = results.get(entry.getKey());
                            if (result == null) {
                                throw new IllegalStateException("Result " + entry.getKey() + " was requested but not produced");
                            }
                            var tmpFile = Paths.get(entry.getValue() + ".tmp");
                            Files.copy(result, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                            FileUtil.atomicMove(tmpFile, entry.getValue());
                        }
                    }
                } else {
                    engine.loadNeoFormData(MavenCoordinate.parse(sourceArtifacts.neoform), dist);
                }
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

    public static void main(String... args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
