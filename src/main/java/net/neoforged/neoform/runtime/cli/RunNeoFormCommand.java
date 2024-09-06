package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.actions.ApplySourceTransformAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.MergeWithSourcesAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "run", description = "Run the NeoForm engine and produce Minecraft artifacts")
public class RunNeoFormCommand extends NeoFormEngineCommand {
    private static final Logger LOG = Logger.create();

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    SourceArtifacts sourceArtifacts;

    @CommandLine.Option(names = "--dist", required = true)
    String dist;

    @CommandLine.Option(names = "--write-result", arity = "*")
    List<String> writeResults = new ArrayList<>();

    @CommandLine.Option(names = "--access-transformer", arity = "*", description = "path to an access transformer file, which widens the access modifiers of classes/methods/fields")
    List<String> additionalAccessTransformers = new ArrayList<>();

    @CommandLine.Option(names = "--interface-injection-data", arity = "*", description = "path to an interface injection data file, which extends classes with implements/extends clauses")
    List<Path> interfaceInjectionDataFiles = new ArrayList<>();

    @CommandLine.Option(names = "--validate-access-transformers", description = "Whether access transformers should be validated and fatal errors should arise if they target members that do not exist")
    boolean validateAccessTransformers;

    @CommandLine.Option(names = "--parchment-data", description = "Path or Maven coordinates of parchment data to use")
    String parchmentData;

    @CommandLine.Option(names = "--parchment-conflict-prefix", description = "Setting this option enables automatic Parchment parameter conflict resolution and uses this prefix for parameter names that clash.")
    String parchmentConflictPrefix;

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

            var transformSources = getOrAddTransformSourcesAction(engine);

            transformSources.setAccessTransformersData(List.of("neoForgeAccessTransformers"));

            // When source remapping is in effect, we would normally have to remap the NeoForge sources as well
            // To circumvent this, we inject the sources before recompile instead.
            if (engine.getProcessGeneration().sourcesUseSrgNames()) {
                engine.applyTransforms(List.of(
                        new ModifyAction<>(
                                "inject",
                                InjectZipContentAction.class,
                                action -> {
                                    // Annoyingly, Forge only had the Java sources in the sources artifact, so we have to pull the other files from the universal jar
                                    action.getInjectedSources().add(new InjectFromZipFileSource(neoforgeClassesZip, "/", Pattern.compile("^(?!.*\\.class$).*")));
                                    action.getInjectedSources().add(new InjectFromZipFileSource(neoforgeSourcesZip, "/"));
                                }
                        )
                ));
            }

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
                        return PatchActionFactory.makeAction(builder,
                                neoforgeArtifact.path(),
                                neoforgeConfig.patchesFolder(),
                                previousOutput,
                                Objects.requireNonNullElse(neoforgeConfig.basePathPrefix(), "a/"),
                                Objects.requireNonNullElse(neoforgeConfig.modifiedPathPrefix(), "b/"));
                    }
            ));

            engine.applyTransforms(transforms);

            var sourcesWithNeoForgeOutput = createSourcesWithNeoForge(engine, neoforgeSourcesZip);
            var compiledWithNeoForgeOutput = createCompiledWithNeoForge(engine, neoforgeClassesZip);

            createSourcesAndCompiledWithNeoForge(engine.getGraph(), compiledWithNeoForgeOutput, sourcesWithNeoForgeOutput);
        } else {
            engine.loadNeoFormData(MavenCoordinate.parse(sourceArtifacts.neoform), dist);
        }

        if (!additionalAccessTransformers.isEmpty()) {
            var transformSources = getOrAddTransformSourcesAction(engine);
            transformSources.setAdditionalAccessTransformers(additionalAccessTransformers.stream().map(Paths::get).toList());
            if (validateAccessTransformers) {
                transformSources.addArg("--access-transformer-validation=error");
            }
        }

        if (parchmentData != null) {
            var parchmentDataFile = artifactManager.get(parchmentData);
            Consumer<ApplySourceTransformAction> jstConsumer = transformSources -> {
                transformSources.setParchmentData(parchmentDataFile.path());
                if (parchmentConflictPrefix != null) {
                    transformSources.addArg("--parchment-conflict-prefix=" + parchmentConflictPrefix);
                }
            };
            // Before 1.20.2, sources were still in SRG, while parchment was defined using Mojang names.
            // Hence, we need to apply Parchment after we remap SRG to Mojang names
            if (engine.getProcessGeneration().sourcesUseSrgNames()) {
                engine.applyTransform(new ReplaceNodeOutput("remapSrgSourcesToOfficial", "output", "applyParchment", sourceTransform(jstConsumer)));
            } else {
                jstConsumer.accept(getOrAddTransformSourcesAction(engine));
            }
        }

        if (!interfaceInjectionDataFiles.isEmpty()) {
            var transformNode = getOrAddTransformSourcesNode(engine);
            ((ApplySourceTransformAction) transformNode.action()).setInjectedInterfaces(interfaceInjectionDataFiles);

            // Add the stub source jar to the recomp classpath
            engine.applyTransform(new ModifyAction<>(
                    "recompile",
                    RecompileSourcesAction.class,
                    action -> {
                        action.getSourcepath().add(ClasspathItem.of(transformNode.getRequiredOutput("stubs")));
                    }
            ));
        }

        execute(engine);
    }

    private static NodeOutput createCompiledWithNeoForge(NeoFormEngine engine, ZipFile neoforgeClassesZip) {
        var graph = engine.getGraph();
        var recompiledClasses = graph.getRequiredOutput("recompile", "output");

        // In older processes, we already had to inject the sources before recompiling (due to remapping)
        if (engine.getProcessGeneration().sourcesUseSrgNames()) {
            graph.setResult("compiledWithNeoForge", recompiledClasses);
            return recompiledClasses;
        }

        // Add a step that produces a classes-zip containing both Minecraft and NeoForge classes
        var builder = graph.nodeBuilder("compiledWithNeoForge");
        builder.input("input", recompiledClasses.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "JAR containing NeoForge classes, resources and Minecraft classes");
        builder.action(new InjectZipContentAction(List.of(
                new InjectFromZipFileSource(neoforgeClassesZip, "/")
        )));
        builder.build();

        graph.setResult("compiledWithNeoForge", output);
        return output;
    }

    // Add a step that produces a sources-zip containing both Minecraft and NeoForge sources
    private static NodeOutput createSourcesWithNeoForge(NeoFormEngine engine, ZipFile neoforgeSourcesZip) {
        var graph = engine.getGraph();

        if (engine.getProcessGeneration().sourcesUseSrgNames()) {
            // 1.20.1 and below use SRG in production and for ATs, so we cannot use the JST output as it is in SRG
            // therefore we must output the renamed sources
            var remapSrgSourcesToOfficialOutput = graph.getRequiredOutput("remapSrgSourcesToOfficial", "output");
            graph.setResult("sourcesWithNeoForge", remapSrgSourcesToOfficialOutput);
            return remapSrgSourcesToOfficialOutput;
        } else {
            var transformedSourceOutput = graph.getRequiredOutput("transformSources", "output");

            var builder = graph.nodeBuilder("sourcesWithNeoForge");
            builder.input("input", transformedSourceOutput.asInput());
            var output = builder.output("output", NodeOutputType.ZIP, "Source ZIP containing NeoForge and Minecraft sources");
            builder.action(new InjectZipContentAction(List.of(
                    new InjectFromZipFileSource(neoforgeSourcesZip, "/")
            )));
            builder.build();
            graph.setResult("sourcesWithNeoForge", output);
            return output;
        }
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

        var neededResults = writeResults.stream().<String[]>map(encodedResult -> {
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

    private static ApplySourceTransformAction getOrAddTransformSourcesAction(NeoFormEngine engine) {
        return (ApplySourceTransformAction) getOrAddTransformSourcesNode(engine).action();
    }

    private static ExecutionNode getOrAddTransformSourcesNode(NeoFormEngine engine) {
        var graph = engine.getGraph();
        var transformNode = graph.getNode("transformSources");
        if (transformNode != null) {
            if (transformNode.action() instanceof ApplySourceTransformAction) {
                return transformNode;
            } else {
                throw new IllegalStateException("Node transformSources has a different action type than expected. Expected: "
                                                + ApplySourceTransformAction.class + " but got " + transformNode.action().getClass());
            }
        }

        new ReplaceNodeOutput(
                "patch",
                "output",
                "transformSources",
                sourceTransform(applySourceTransformAction -> {
                })
        ).apply(engine, graph);

        return getOrAddTransformSourcesNode(engine);
    }

    private static ReplaceNodeOutput.NodeFactory sourceTransform(Consumer<ApplySourceTransformAction> actionConsumer) {
        return (builder, previousNodeOutput) -> {
            builder.input("input", previousNodeOutput.asInput());
            builder.inputFromNodeOutput("libraries", "listLibraries", "output");
            var action = new ApplySourceTransformAction();
            builder.action(action);
            actionConsumer.accept(action);
            builder.output("stubs", NodeOutputType.JAR, "Additional stubs (resulted as part of interface injection) to add to the recompilation classpath");
            return builder.output("output", NodeOutputType.ZIP, "Sources with additional transforms (ATs, Parchment, Interface Injections) applied");
        };
    }
}
