package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.actions.ApplySourceAccessTransformersAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.MergeWithSourcesAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.transforms.GraphTransform;
import net.neoforged.neoform.runtime.graph.transforms.ModifyAction;
import net.neoforged.neoform.runtime.graph.transforms.ReplaceNodeOutput;
import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "run", description = "Run the NeoForm engine and produce Minecraft artifacts")
public class RunNeoFormCommand extends NeoFormEngineCommand {
    private static final Logger LOG = Logger.create();

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

    @CommandLine.Option(names = "--parchment-data", description = "Path or Maven coordinates of parchment data to use")
    String parchmentData;

    static class SourceArtifacts {
        @CommandLine.Option(names = "--neoform")
        String neoform;
        @CommandLine.Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
        var artifactManager = engine.getArtifactManager();

        if (sourceArtifacts.neoforge != null) {
            var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
            var neoforgeZipFile = engine.addManagedResource(new JarFile(neoforgeArtifact.path().toFile()));
            var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);
            var neoformArtifact = MavenCoordinate.parse(neoforgeConfig.neoformArtifact());
            // Allow it to be overridden
            if (sourceArtifacts.neoform != null) {
                LOG.println("Overriding NeoForm version " + neoformArtifact + " with CLI argument " + sourceArtifacts.neoform);
                neoformArtifact = MavenCoordinate.parse(sourceArtifacts.neoform);
            }

            engine.loadNeoFormData(neoformArtifact, dist);

            // Add NeoForge specific data sources
            engine.addDataSource("neoForgeAccessTransformers", neoforgeZipFile, neoforgeConfig.accessTransformersFolder());

            // Build the graph transformations needed to apply NeoForge to the NeoForm execution
            List<GraphTransform> transforms = new ArrayList<>();

            // Also inject NeoForge sources, which we can get from the sources file
            var neoforgeSources = artifactManager.get(neoforgeConfig.sourcesArtifact()).path();
            var neoforgeClasses = artifactManager.get(neoforgeConfig.universalArtifact()).path();
            var neoforgeSourcesZip = new ZipFile(neoforgeSources.toFile());
            var neoforgeClassesZip = new ZipFile(neoforgeClasses.toFile());
            engine.addManagedResource(neoforgeSourcesZip);
            engine.addManagedResource(neoforgeClassesZip);

            var transformSources = getOrAddTransformSourcesNode(engine);
            transformSources.setAccessTransformersData(List.of("neoForgeAccessTransformers"));
            transformSources.setAdditionalAccessTransformers(additionalAccessTransformers.stream().map(Paths::get).toList());

            // Add NeoForge libraries to the list of libraries
            transforms.add(new ModifyAction<>(
                    "recompile",
                    RecompileSourcesAction.class,
                    action -> {
                        action.getClasspath().addMavenLibraries(neoforgeConfig.libraries());
                        action.getClasspath().addPaths(List.of(neoforgeClasses));
                    }
            ));

            // Append a patch step to the NeoForge patches
            transforms.add(new ReplaceNodeOutput("patch", "output", "applyNeoforgePatches",
                    (builder, previousOutput) -> {
                        return PatchActionFactory.makeAction(builder, neoforgeArtifact.path(), neoforgeConfig.patchesFolder(), previousOutput);
                    }
            ));

            engine.applyTransforms(transforms);

            var graph = engine.getGraph();

            var sourcesWithNeoForgeOutput = createSourcesWithNeoForge(graph, neoforgeSourcesZip);

            var compiledWithNeoForgeOutput = createCompiledWithNeoForge(graph, neoforgeClassesZip);

            createSourcesAndCompiledWithNeoForge(graph, compiledWithNeoForgeOutput, sourcesWithNeoForgeOutput);
        } else {
            engine.loadNeoFormData(MavenCoordinate.parse(sourceArtifacts.neoform), dist);
        }

        if (parchmentData != null) {
            var transformSources = getOrAddTransformSourcesNode(engine);
            var parchmentDataFile = artifactManager.get(parchmentData);
            transformSources.setParchmentData(parchmentDataFile.path());
        }

        execute(engine);
    }

    private static NodeOutput createCompiledWithNeoForge(ExecutionGraph graph, ZipFile neoforgeClassesZip) {
        // Add a step that produces a classes-zip containing both Minecraft and NeoForge classes
        var builder = graph.nodeBuilder("compiledWithNeoForge");
        builder.input("input", graph.getRequiredOutput("recompile", "output").asInput());
        var output = builder.output("output", NodeOutputType.JAR, "JAR containing NeoForge classes, resources and Minecraft classes");
        builder.action(new InjectZipContentAction(List.of(
                new InjectFromZipFileSource(neoforgeClassesZip, "/")
        )));
        builder.build();
        graph.setResult("compiledWithNeoForge", output);
        return output;
    }

    private static NodeOutput createSourcesWithNeoForge(ExecutionGraph graph, ZipFile neoforgeSourcesZip) {
        // Add a step that produces a sources-zip containing both Minecraft and NeoForge sources
        var builder = graph.nodeBuilder("sourcesWithNeoForge");
        builder.input("input", graph.getRequiredOutput("transformSources", "output").asInput());
        var output = builder.output("output", NodeOutputType.ZIP, "Source ZIP containing NeoForge and Minecraft sources");
        builder.action(new InjectZipContentAction(List.of(
                new InjectFromZipFileSource(neoforgeSourcesZip, "/")
        )));
        builder.build();
        graph.setResult("sourcesWithNeoForge", output);
        return output;
    }

    private static void createSourcesAndCompiledWithNeoForge(ExecutionGraph graph, NodeOutput compiledWithNeoForgeOutput, NodeOutput sourcesWithNeoForgeOutput) {
        // Add a step that merges sources and compiled classes to satisfy IntelliJ
        var builder = graph.nodeBuilder("sourcesAndCompiledWithNeoForge");
        builder.input("classes", compiledWithNeoForgeOutput.asInput());
        builder.input("sources", sourcesWithNeoForgeOutput.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "Combined output of sourcesWithNeoForge and compiledWithNeoForge");
        builder.action(new MergeWithSourcesAction());
        builder.build();
        graph.setResult("sourcesAndCompiledWithNeoForge", output);
    }

    private void execute(NeoFormEngine engine) throws InterruptedException, IOException {
        if (printGraph) {
            var stringWriter = new StringWriter();
            engine.dumpGraph(new PrintWriter(stringWriter));
            LOG.println(stringWriter.toString());
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
            var resultFileHash = HashingUtil.hashFile(result, "SHA-1");
            try {
                if (HashingUtil.hashFile(entry.getValue(), "SHA-1").equals(resultFileHash)) {
                    continue; // Nothing to do the file already matches
                }
            } catch (NoSuchFileException ignored) {
            }

            var tmpFile = Paths.get(entry.getValue() + ".tmp");
            Files.copy(result, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            FileUtil.atomicMove(tmpFile, entry.getValue());
        }
    }

    private static ApplySourceAccessTransformersAction getOrAddTransformSourcesNode(NeoFormEngine engine) {
        var graph = engine.getGraph();
        var transformNode = graph.getNode("transformSources");
        if (transformNode != null) {
            if (transformNode.action() instanceof ApplySourceAccessTransformersAction action) {
                return action;
            } else {
                throw new IllegalStateException("Node transformSources has a different action type than expected. Expected: "
                                                + ApplySourceAccessTransformersAction.class + " but got " + transformNode.action().getClass());
            }
        }

        new ReplaceNodeOutput(
                "patch",
                "output",
                "transformSources",
                (builder, previousNodeOutput) -> {
                    builder.input("input", previousNodeOutput.asInput());
                    builder.inputFromNodeOutput("libraries", "listLibraries", "output");
                    var action = new ApplySourceAccessTransformersAction();
                    builder.action(action);
                    return builder.output("output", NodeOutputType.ZIP, "Sources with additional transforms (ATs, Parchment) applied");
                }
        ).apply(engine, graph);

        return getOrAddTransformSourcesNode(engine);
    }
}
