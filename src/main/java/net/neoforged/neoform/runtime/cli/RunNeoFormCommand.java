package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.actions.ApplySourceAccessTransformersAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.transforms.GraphTransform;
import net.neoforged.neoform.runtime.graph.transforms.ModifyAction;
import net.neoforged.neoform.runtime.graph.transforms.ReplaceNodeOutput;
import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "run", description = "Run the NeoForm engine and produce Minecraft artifacts")
public class RunNeoFormCommand extends NeoFormEngineCommand {
    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    SourceArtifacts sourceArtifacts;

    @CommandLine.Option(names = "--dist", required = true)
    String dist;

    @CommandLine.Option(names = "--write-result", arity = "*")
    List<String> writeResults = new ArrayList<>();

    @CommandLine.Option(names = "--access-transformer", arity = "*")
    List<String> additionalAccessTransformers = new ArrayList<>();

    static class SourceArtifacts {
        @CommandLine.Option(names = "--neoform")
        String neoform;
        @CommandLine.Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
        var artifactManager = engine.getArtifactManager();

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
                        RecompileSourcesAction.class,
                        action -> {
                            action.getClasspath().addMavenLibraries(neoforgeConfig.libraries());
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
                            var action = new ApplySourceAccessTransformersAction("neoForgeAccessTransformers");
                            action.setAdditionalAccessTransformers(additionalAccessTransformers.stream().map(Paths::get).toList());
                            builder.action(action);
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

                execute(engine);
            }
        } else {
            engine.loadNeoFormData(MavenCoordinate.parse(sourceArtifacts.neoform), dist);

            execute(engine);
        }
    }

    private void execute(NeoFormEngine engine) throws InterruptedException, IOException {
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
}

