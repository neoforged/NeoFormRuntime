package net.neoforged.neoform.runtime.cli;

import com.google.gson.JsonObject;
import net.neoforged.neoform.runtime.actions.ApplyDevTransformsAction;
import net.neoforged.neoform.runtime.actions.ApplySourceTransformAction;
import net.neoforged.neoform.runtime.actions.CopyUnpatchedClassesAction;
import net.neoforged.neoform.runtime.actions.ExternalJavaToolAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.MergeWithSourcesAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.actions.StripManifestDigestContentFilter;
import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import net.neoforged.neoform.runtime.config.neoforge.BinpatcherConfig;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.engine.DataSource;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.NodeInput;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.transforms.ModifyAction;
import net.neoforged.neoform.runtime.graph.transforms.ReplaceNodeInput;
import net.neoforged.neoform.runtime.graph.transforms.ReplaceNodeOutput;
import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "run", description = "Run the NeoForm engine and produce Minecraft artifacts")
public class RunNeoFormCommand extends NeoFormEngineCommand {
    private static final Logger LOG = Logger.create();

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    SourceArtifacts sourceArtifacts;

    @CommandLine.Option(names = "--dist", defaultValue = "joined")
    String dist;

    @CommandLine.Option(names = "--write-result", arity = "*")
    List<String> writeResults = new ArrayList<>();

    @CommandLine.Option(names = "--access-transformer", arity = "*", description = "path to an access transformer file, which widens the access modifiers of classes/methods/fields")
    List<String> additionalAccessTransformers = new ArrayList<>();

    @CommandLine.Option(names = "--validated-access-transformer", arity = "*", description = "same as --access-transformer, but files added using this option will fail the build if they contain targets that do not exist.")
    List<String> validatedAccessTransformers = new ArrayList<>();

    @CommandLine.Option(names = "--interface-injection-data", arity = "*", description = "path to an interface injection data file, which extends classes with implements/extends clauses.")
    List<Path> interfaceInjectionDataFiles = new ArrayList<>();

    @Deprecated
    @CommandLine.Option(names = "--validate-access-transformers", description = "[DEPRECATED] Use --validated-access-transformer instead")
    boolean validateAccessTransformers;

    @CommandLine.Option(names = "--parchment-data", description = "Path or Maven coordinates of parchment data to use")
    String parchmentData;

    @CommandLine.Option(names = "--parchment-conflict-prefix", description = "Setting this option enables automatic Parchment parameter conflict resolution and uses this prefix for parameter names that clash.")
    String parchmentConflictPrefix;

    @CommandLine.Option(names = "--mcp-mapping-data", description = "Path or Maven coordinates of MCP mapping data to use for pre-1.17 Minecraft")
    String mcpMappingData;

    static class SourceArtifacts {
        @CommandLine.Option(names = "--neoform")
        String neoform;
        @CommandLine.Option(names = "--neoforge")
        String neoforge;
    }

    @Override
    protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
        var artifactManager = engine.getArtifactManager();

        if (mcpMappingData != null) {
            engine.setMcpMappingsData(artifactManager.get(mcpMappingData).path());
        }

        if (sourceArtifacts.neoforge != null) {
            var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
            var neoforgeZipFile = engine.addManagedResource(new JarFile(neoforgeArtifact.path().toFile()));
            var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);

            // Allow it to be overridden with local or remote data
            Path neoformArtifact;
            if (sourceArtifacts.neoform != null) {
                LOG.println("Overriding NeoForm version " + neoforgeConfig.neoformArtifact() + " with CLI argument " + sourceArtifacts.neoform);
                neoformArtifact = artifactManager.get(sourceArtifacts.neoform).path();
            } else {
                neoformArtifact = artifactManager.get(neoforgeConfig.neoformArtifact()).path();
            }

            engine.loadNeoFormData(neoformArtifact, dist);

            applyNeoForgeProcessTransforms(engine, neoforgeZipFile, neoforgeConfig);
        } else {
            var neoFormDataPath = artifactManager.get(sourceArtifacts.neoform).path();

            engine.loadNeoFormData(neoFormDataPath, dist);
        }

        applyAdditionalAccessTransformers(engine);

        if (parchmentData != null) {
            var parchmentDataFile = artifactManager.get(parchmentData);
            Consumer<ApplySourceTransformAction> jstConsumer = transformSources -> {
                transformSources.setParchmentData(parchmentDataFile.path());
                if (parchmentConflictPrefix != null) {
                    transformSources.addArg("--parchment-conflict-prefix=" + parchmentConflictPrefix);
                }
                // Don't rename parameters in versions that aren't obfuscated. This can cause changes in semantics
                // when suddenly a reference to a parameter refers to a captured local variable instead.
                if (engine.getGraph().getNode("rename") == null) {
                    transformSources.addArg("--no-parchment-parameters");
                }
            };
            // Before 1.20.2, sources were still in SRG, while parchment was defined using Mojang names.
            // Hence, we need to apply Parchment after we remap SRG to Mojang names
            if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
                engine.applyTransform(new ReplaceNodeOutput("remapSrgSourcesToOfficial", "output", "applyParchment", sourceTransform(engine, jstConsumer)));
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

        // Transformations for the binpatch pipeline
        if (!additionalAccessTransformers.isEmpty() || !validatedAccessTransformers.isEmpty() || !interfaceInjectionDataFiles.isEmpty()) {
            var graph = engine.getGraph();
            // The node can be created by the NeoForge process (see applyNeoForgeProcessTransforms)
            var transformNode = graph.getNode("applyDevTransforms");
            if (transformNode == null) {
                transformNode = createBinaryDevTransformNodeForNeoForm(engine);
            }
            if (!(transformNode.action() instanceof ApplyDevTransformsAction applyDevTransformsAction)) {
                throw new IllegalStateException("Node applyDevTransforms has a different action type than expected. Expected: "
                        + ApplyDevTransformsAction.class + " but got " + transformNode.action().getClass());
            }

            var allAts = new ArrayList<Path>();
            allAts.addAll(additionalAccessTransformers.stream().map(Paths::get).toList());
            allAts.addAll(validatedAccessTransformers.stream().map(Paths::get).toList());
            applyDevTransformsAction.setAdditionalAccessTransformers(allAts);
            applyDevTransformsAction.setInjectedInterfaces(interfaceInjectionDataFiles);
        }

        execute(engine);
    }

    private static void applyNeoForgeProcessTransforms(NeoFormEngine engine, JarFile neoforgeZipFile, NeoForgeConfig neoforgeConfig) throws IOException {
        // Add NeoForge specific data sources
        engine.addDataSource("neoForgeAccessTransformers", neoforgeZipFile, neoforgeConfig.accessTransformersFolder());

        // Also inject NeoForge sources, which we can get from the sources file
        var artifactManager = engine.getArtifactManager();
        var neoforgeSources = artifactManager.get(neoforgeConfig.sourcesArtifact()).path();
        var neoforgeClasses = artifactManager.get(neoforgeConfig.universalArtifact()).path();
        var neoforgeSourcesZip = new ZipFile(neoforgeSources.toFile());
        var neoforgeClassesZip = new ZipFile(neoforgeClasses.toFile());
        engine.addManagedResource(neoforgeSourcesZip);
        engine.addManagedResource(neoforgeClassesZip);

        var transformSources = getOrAddTransformSourcesAction(engine);

        transformSources.setAccessTransformersData(List.of("neoForgeAccessTransformers"));

        // When source remapping is in effect, we would normally have to remap the NeoForge sources as well
        // To circumvent this, we inject the sources before recompile and disable the optimization of
        // injecting the already compiled NeoForge classes later.
        // Since remapping and recompiling will invariably change the digests, we also need to strip any signatures.
        if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
            engine.applyTransform(new ModifyAction<>(
                    "inject",
                    InjectZipContentAction.class,
                    action -> {
                        // Annoyingly, Forge only had the Java sources in the sources artifact.
                        // We have to pull resources from the universal jar.
                        action.getInjectedSources().add(new InjectFromZipFileSource(
                                neoforgeClassesZip,
                                "/",
                                Pattern.compile("^(?!META-INF/[^/]+\\.(SF|RSA|DSA|EC)$|.*\\.class$).*"),
                                StripManifestDigestContentFilter.INSTANCE
                        ));
                        action.getInjectedSources().add(new InjectFromZipFileSource(
                                neoforgeSourcesZip,
                                "/",
                                // The MCF sources have a bogus MANIFEST that should be ignored
                                Pattern.compile("^(?!META-INF/MANIFEST.MF$).*")
                        ));
                    }
            ));
        }

        // Add NeoForge libraries to the list of libraries
        engine.applyTransform(new ModifyAction<>(
                "recompile",
                RecompileSourcesAction.class,
                action -> {
                    action.getClasspath().addMavenLibraries(neoforgeConfig.libraries());
                    action.getClasspath().addPaths(List.of(neoforgeClasses));
                }
        ));

        // If the Forge (or NeoForge) version uses side annotation strippers, apply them after merging
        // This is a legacy concept, see https://github.com/MinecraftForge/ForgeGradle/blob/477b8685abcfe76755c2d49f60b07fabbfdb8b5f/src/mcp/java/net/minecraftforge/gradle/mcp/function/SideAnnotationStripperFunction.java#L24
        if (engine.getProcessGeneration().supportsSideAnnotationStripping()) {
            List<String> sasFiles = neoforgeConfig.sideAnnotationStrippers();
            if (!sasFiles.isEmpty()) {
                for (int i = 0; i < sasFiles.size(); i++) {
                    engine.addDataSource("sasFile" + i, neoforgeZipFile, sasFiles.get(i));
                }

                engine.applyTransform(new ReplaceNodeInput("decompile", "input", "stripSideAnnotations",
                        (builder, previousInput) -> {
                            builder.input("input", previousInput);

                            ExternalJavaToolAction action = new ExternalJavaToolAction(ToolCoordinate.MCF_SIDE_ANNOTATION_STRIPPER);
                            List<String> args = new ArrayList<>();
                            Collections.addAll(args, "--strip", "--input", "{input}", "--output", "{output}");
                            for (int i = 0; i < sasFiles.size(); i++) {
                                args.add("--data");
                                args.add("{sasFile" + i + "}");
                            }
                            action.setArgs(args);
                            builder.action(action);

                            return builder.output("output", NodeOutputType.JAR, "The jar file with the desired side annotations removed");
                        }
                ));
            }
        }

        // Source post-processors were used to post-process the decompiler output before applying the NF patches.
        // Example version: 1.12.2.
        var nfPatchesInputNode = "patch";
        var sourcePreProcessor = neoforgeConfig.sourcePreProcessor();
        if (sourcePreProcessor != null) {
            engine.applyTransform(new ReplaceNodeOutput(
                            "patch", "output", "applyUserdevSourcePreprocessor",
                            (builder, previousOutput) -> {
                                var newOutput = engine.applyFunctionToNode(neoforgeConfig.libraries(), Map.of(
                                        // Provide the output of patch as the input
                                        "input", "{patchOutput}"
                                ), NodeOutputType.ZIP, sourcePreProcessor, builder);
                                return Objects.requireNonNull(newOutput);
                            }
                    )
            );
            // Patches now need to use this node as input
            nfPatchesInputNode = "applyUserdevSourcePreprocessor";
        }

        // Append a patch step to the NeoForge patches
        engine.applyTransform(new ReplaceNodeOutput(nfPatchesInputNode, "output", "applyNeoforgePatches",
                (builder, previousOutput) -> {
                    return PatchActionFactory.makeAction(builder,
                            new DataSource(neoforgeZipFile, neoforgeConfig.patchesFolder(), engine.getFileHashingService()),
                            previousOutput,
                            Objects.requireNonNullElse(neoforgeConfig.basePathPrefix(), "a/"),
                            Objects.requireNonNullElse(neoforgeConfig.modifiedPathPrefix(), "b/"));
                }
        ));

        var graph = engine.getGraph();
        var sourcesWithNeoForgeOutput = createSourcesWithNeoForge(engine, neoforgeSourcesZip);
        var compiledWithNeoForgeOutput = createCompiledWithNeoForge(engine, neoforgeClassesZip);

        var sourcesAndCompiledWithNeoForgeOutput =
                createSourcesAndCompiledWithNeoForge(graph, compiledWithNeoForgeOutput, sourcesWithNeoForgeOutput);

        graph.setResult(ResultIds.GAME_SOURCES_WITH_NEOFORGE, sourcesWithNeoForgeOutput);
        graph.setResult(ResultIds.GAME_JAR_WITH_NEOFORGE, compiledWithNeoForgeOutput);
        graph.setResult(ResultIds.GAME_JAR_WITH_SOURCES_AND_NEOFORGE, sourcesAndCompiledWithNeoForgeOutput);

        applyNeoForgeBinaryPatchProcessTransforms(engine, neoforgeZipFile, neoforgeConfig, neoforgeClassesZip);

    }

    private static void applyNeoForgeBinaryPatchProcessTransforms(NeoFormEngine engine,
                                                                  JarFile neoforgeZipFile,
                                                                  NeoForgeConfig neoforgeConfig,
                                                                  ZipFile neoforgeClassesZip) {
        var graph = engine.getGraph();
        var patchBaseJar = graph.getResult(ResultIds.VANILLA_DEOBFUSCATED);

        engine.addDataSource("patch", neoforgeZipFile, neoforgeConfig.binaryPatchesFile());
        var binaryPatchOutput = createBinaryPatch(graph, patchBaseJar, neoforgeConfig.binaryPatcherConfig());
        binaryPatchOutput = createCopyUnpatchedClasses(graph, patchBaseJar, binaryPatchOutput);

        // For binpatches we also need to consider Access Transforms / Interface Injection
        // However, we have to force-create the node here, since it's placement with NeoForge enabled
        // is very different from when it is placed by the NeoForm process.
        binaryPatchOutput = createBinaryDevTransformNode(graph, binaryPatchOutput.asInput());
        ((ApplyDevTransformsAction) binaryPatchOutput.getNode().action()).setAccessTransformersData(List.of("neoForgeAccessTransformers"));

        // This is a new result here
        var binaryWithNeoForgeOutput = createBinaryWithNeoForge(graph, binaryPatchOutput, neoforgeClassesZip);

        if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
            // Minecraft and NeoForge classes need to be remapped,
            // so we only expose jars that contains both (similar to the standard decomp/recomp pipeline)
            var remapper = graph.getRequiredNode("remapSrgClassesToOfficial");
            remapper.setInput("input", binaryWithNeoForgeOutput.asInput());
            var remappedOutput = remapper.getRequiredOutput("output");
            graph.setResult(ResultIds.GAME_JAR_NO_RECOMP, remappedOutput); // technically redundant, but set again for clarity
            graph.setResult(ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE, remappedOutput);

        } else {
            graph.setResult(ResultIds.GAME_JAR_NO_RECOMP, binaryPatchOutput);
            graph.setResult(ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE, binaryWithNeoForgeOutput);
        }
    }

    /**
     * Configure the engine to apply additional user-supplied access transformers to the game sources.
     */
    private void applyAdditionalAccessTransformers(NeoFormEngine engine) {
        if (!additionalAccessTransformers.isEmpty() || !validatedAccessTransformers.isEmpty()) {
            var transformSources = getOrAddTransformSourcesAction(engine);
            transformSources.setAdditionalAccessTransformers(additionalAccessTransformers.stream().map(Paths::get).toList());
            transformSources.setValidatedAccessTransformers(validatedAccessTransformers.stream().map(Paths::get).toList());

            if (validateAccessTransformers) {
                transformSources.addArg("--access-transformer-validation=error");
            }
        }
    }

    private static NodeOutput createCompiledWithNeoForge(NeoFormEngine engine, ZipFile neoforgeClassesZip) {
        var graph = engine.getGraph();
        var recompiledClasses = graph.getRequiredOutput("recompile", "output");

        // In older processes, we already had to inject the sources before recompiling (due to remapping)
        if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
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

        return output;
    }

    // Add a step that produces a sources-zip containing both Minecraft and NeoForge sources
    private static NodeOutput createSourcesWithNeoForge(NeoFormEngine engine, ZipFile neoforgeSourcesZip) {
        var graph = engine.getGraph();

        if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
            // 1.20.1 and below use SRG in production and for ATs, so we cannot use the JST output as it is in SRG
            // therefore we must output the renamed sources
            return graph.getRequiredOutput("remapSrgSourcesToOfficial", "output");
        } else {
            var transformedSourceOutput = graph.getRequiredOutput("transformSources", "output");

            var builder = graph.nodeBuilder("sourcesWithNeoForge");
            builder.input("input", transformedSourceOutput.asInput());
            var output = builder.output("output", NodeOutputType.ZIP, "Source ZIP containing NeoForge and Minecraft sources");
            builder.action(new InjectZipContentAction(List.of(
                    new InjectFromZipFileSource(neoforgeSourcesZip, "/")
            )));
            builder.build();
            return output;
        }
    }

    private static NodeOutput createSourcesAndCompiledWithNeoForge(ExecutionGraph graph, NodeOutput compiledWithNeoForgeOutput, NodeOutput sourcesWithNeoForgeOutput) {
        // Add a step that merges sources and compiled classes to satisfy IntelliJ
        var builder = graph.nodeBuilder("sourcesAndCompiledWithNeoForge");
        builder.input("classes", compiledWithNeoForgeOutput.asInput());
        builder.input("sources", sourcesWithNeoForgeOutput.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "Combined output of sourcesWithNeoForge and compiledWithNeoForge");
        builder.action(new MergeWithSourcesAction());
        builder.build();
        return output;
    }

    private static NodeOutput createBinaryPatch(ExecutionGraph graph, NodeOutput clean, BinpatcherConfig config) {
        var builder = graph.nodeBuilder("binaryPatch");
        builder.input("clean", clean.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "JAR containing the patched Minecraft classes");
        var action = new ExternalJavaToolAction(MavenCoordinate.parse(config.version()));
        action.setArgs(config.args());
        builder.action(action);
        builder.build();
        return output;
    }

    private static NodeOutput createCopyUnpatchedClasses(ExecutionGraph graph, NodeOutput clean, NodeOutput binaryPatched) {
        var builder = graph.nodeBuilder("copyUnpatchedClasses");
        builder.input("patched", binaryPatched.asInput());
        builder.input("unpatched", clean.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "JAR containing the patched and clean (if not patched) Minecraft classes");
        builder.action(new CopyUnpatchedClassesAction());
        builder.build();
        return output;
    }

    private static NodeOutput createBinaryWithNeoForge(ExecutionGraph graph, NodeOutput binary, ZipFile neoforgeClassesZip) {
        // Add a step that produces a classes-zip containing both Minecraft and NeoForge classes
        var builder = graph.nodeBuilder("binaryWithNeoForge");
        builder.input("input", binary.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "JAR containing NeoForge classes, resources and Minecraft classes");
        builder.action(new InjectZipContentAction(List.of(
                new InjectFromZipFileSource(neoforgeClassesZip, "/")
        )));
        builder.build();

        return output;
    }

    private void execute(NeoFormEngine engine) throws InterruptedException, IOException {
        if (printGraph) {
            var stringWriter = new StringWriter();
            engine.dumpGraph(new PrintWriter(stringWriter));

            // Build a direct link to Mermaid.live
            var bos = new ByteArrayOutputStream();
            try (var dos = new DeflaterOutputStream(bos)) {
                var obj = new JsonObject();
                obj.addProperty("code", stringWriter.toString());
                dos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
            LOG.println("Open in Browser: https://mermaid.live/view#pako:" + Base64.getEncoder().encodeToString(bos.toByteArray()));
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

        if (neededResults.isEmpty() && !printGraph) {
            System.err.println("No results requested using --write-result=<result>:<path>. Available results: " + engine.getGraph().getAvailableResults());
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
                sourceTransform(engine, applySourceTransformAction -> {
                })
        ).apply(engine, graph);

        return getOrAddTransformSourcesNode(engine);
    }

    private static ReplaceNodeOutput.NodeFactory sourceTransform(NeoFormEngine engine, Consumer<ApplySourceTransformAction> actionConsumer) {
        return (builder, previousNodeOutput) -> {
            builder.input("input", previousNodeOutput.asInput());
            builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
            var action = new ApplySourceTransformAction();
            // The source transforms should inherit the classpath from the decompiler
            var decompileAction = (ExternalJavaToolAction) engine.getGraph().getRequiredNode("decompile").action();
            if (decompileAction.getListLibraries() != null) {
                action.getListLibraries().setClasspath(decompileAction.getListLibraries().getClasspath().copy());
            }
            builder.action(action);
            actionConsumer.accept(action);
            builder.output("stubs", NodeOutputType.JAR, "Additional stubs (resulted as part of interface injection) to add to the recompilation classpath");
            return builder.output("output", NodeOutputType.ZIP, "Sources with additional transforms (ATs, Parchment, Interface Injections) applied");
        };
    }

    private static ExecutionNode createBinaryDevTransformNodeForNeoForm(NeoFormEngine engine) {
        NodeOutput transformedOutput;
        var graph = engine.getGraph();
        if (engine.getProcessGeneration().sourcesUseIntermediaryNames()) {
            // We have to transform in srg, and the remapped classes have to remain the result
            var remapNode = graph.getRequiredNode("remapSrgClassesToOfficial");
            var remapInput = remapNode.getRequiredInput("input");
            transformedOutput = createBinaryDevTransformNode(graph, remapInput.copy());
            remapNode.setInput("input", transformedOutput.asInput());
        } else {
            var transformInput = graph.getResult(ResultIds.GAME_JAR_NO_RECOMP);
            transformedOutput = createBinaryDevTransformNode(graph, transformInput.asInput());
            graph.setResult(ResultIds.GAME_JAR_NO_RECOMP, transformedOutput);
        }
        return transformedOutput.getNode();
    }

    private static NodeOutput createBinaryDevTransformNode(ExecutionGraph graph, NodeInput input) {
        var builder = graph.nodeBuilder("applyDevTransforms");
        builder.input("input", input);
        var transformedOutput = builder.output("output", NodeOutputType.JAR, "The jar file with the desired dev transforms applied.");
        builder.action(new ApplyDevTransformsAction());
        builder.build();

        return transformedOutput;
    }
}
