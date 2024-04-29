package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.config.NeoFormConfig;
import net.neoforged.neoforminabox.config.NeoFormDistConfig;
import net.neoforged.neoforminabox.config.NeoFormFunction;
import net.neoforged.neoforminabox.config.NeoFormStep;
import net.neoforged.neoforminabox.graph.ExecutionGraph;
import net.neoforged.neoforminabox.graph.ExecutionNode;
import net.neoforged.neoforminabox.graph.ExecutionNodeBuilder;
import net.neoforged.neoforminabox.graph.NodeExecutionException;
import net.neoforged.neoforminabox.graph.NodeOutputType;
import net.neoforged.neoforminabox.manifests.MinecraftLibrary;
import net.neoforged.neoforminabox.manifests.MinecraftVersionManifest;
import net.neoforged.neoforminabox.tasks.FilterJarContentTask;
import net.neoforged.neoforminabox.tasks.InjectFromZipSource;
import net.neoforged.neoforminabox.tasks.InjectZipContentTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public class NeoFormEngine implements AutoCloseable {
    private final ArtifactManager artifactManager;
    private final ProcessingStepManager processingStepManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ExecutionNode, CompletableFuture<Void>> executingNodes = new IdentityHashMap<>();
    private final ZipFile archive;
    private final NeoFormDistConfig config;

    private NeoFormEngine(ArtifactManager artifactManager, ProcessingStepManager processingStepManager, ZipFile archive, NeoFormDistConfig config) {
        this.artifactManager = artifactManager;
        this.processingStepManager = processingStepManager;
        this.archive = archive;
        this.config = config;
    }

    public void close() throws IOException {
        archive.close();
    }

    public static NeoFormEngine create(ArtifactManager artifactManager, ProcessingStepManager processingStepManager, String neoFormArtifactId, String dist) throws IOException {
        var neoFormArchive = artifactManager.get(neoFormArtifactId);
        var zipFile = new ZipFile(neoFormArchive.path().toFile());
        var neoformConfig = NeoFormConfig.from(zipFile);
        var distConfig = neoformConfig.getDistConfig(dist);
        return new NeoFormEngine(artifactManager, processingStepManager, zipFile, distConfig);
    }

    public void run() throws Exception {
        var graph = buildGraph(config);

        var patchOutput = graph.getRequiredOutput("patch", "output");
        runNode(patchOutput.node());
    }

    private ExecutionGraph buildGraph(NeoFormDistConfig distConfig) {
        var graph = new ExecutionGraph();

        for (var step : distConfig.steps()) {
            addNodeForStep(graph, step);
        }

        return graph;
    }

    private void addNodeForStep(ExecutionGraph graph, NeoFormStep step) {
        var builder = graph.nodeBuilder(step.getId());

        // "variables" should now hold all global variables referenced by the step/function, but those
        //  might still either reference the outputs of other nodes, or entries in the data dictionary.
        for (var entry : step.values().entrySet()) {
            var variables = new HashSet<String>();
            NeoFormInterpolator.collectReferencedVariables(entry.getValue(), variables);

            for (String variable : variables) {
                var resolvedOutput = graph.getOutput(variable);
                if (resolvedOutput == null) {
                    if (config.hasData(variable)) {
                        continue; // it's legal to transitively reference entries in the data dictionary
                    }
                    throw new IllegalArgumentException("Step " + step.type() + " references undeclared output " + variable);
                }
                builder.input(entry.getKey(), resolvedOutput.asInput());
            }
        }

        // If the step has a function, collect the variables that function may reference globally as well.
        // Usually a function should only reference data or step values, but... who knows.
        switch (step.type()) {
            case "downloadManifest" -> {
                builder.output("output", NodeOutputType.JSON, "Launcher Manifest for all Minecraft versions");
                builder.action(environment -> {
                    var artifact = artifactManager.getLauncherManifest();
                    environment.setOutput("output", artifact.path());
                });
            }
            case "downloadJson" -> {
                builder.output("output", NodeOutputType.VERSION_MANIFEST, "Version manifest for a particular Minecraft version");
                builder.action(environment -> {
                    var artifact = artifactManager.getVersionManifest(config.minecraftVersion());
                    var manifest = MinecraftVersionManifest.from(artifact.path());
                    environment.setOutput("output", manifest);
                });
            }
            case "downloadClient" ->
                    createDownloadFromVersionManifest(builder, "client", NodeOutputType.JAR, "The main Minecraft client jar-file.");
            case "downloadServer" ->
                    createDownloadFromVersionManifest(builder, "server", NodeOutputType.JAR, "The main Minecraft server jar-file.");
            case "downloadClientMappings" ->
                    createDownloadFromVersionManifest(builder, "client_mappings", NodeOutputType.TXT, "The official mappings for the Minecraft client jar-file.");
            case "downloadServerMappings" ->
                    createDownloadFromVersionManifest(builder, "server_mappings", NodeOutputType.TXT, "The official mappings for the Minecraft server jar-file.");
            case "strip" -> {
                builder.output("output", NodeOutputType.JAR, "The jar-file with only classes remaining");
                builder.action(environment -> {
                    var inputJar = environment.getRequiredInputPath("input");
                    var outputJar = environment.getOutputPath("output");
                    new FilterJarContentTask(inputJar, outputJar, true, Set.of()).run();
                });
            }
            case "listLibraries" -> {
                builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
                builder.output("output", NodeOutputType.TXT, "A list of all external JAR files needed to decompile/recompile");
                builder.action(environment -> {
                    var versionManifest = environment.getRequiredInput("versionManifest", MinecraftVersionManifest.class);
                    var libraries = versionManifest.libraries()
                            .stream()
                            .filter(MinecraftLibrary::rulesMatch)
                            .filter(library -> library.downloads().artifact() != null)
                            .collect(Collectors.toSet());

                    var lines = new ArrayList<String>();
                    for (var library : libraries) {
                        var artifact = artifactManager.get(library);
                        lines.add("-e=" + artifact.path().toAbsolutePath());
                    }

                    // Add libraries added by neoform
                    for (var artifactId : config.libraries()) {
                        var artifact = artifactManager.get(artifactId);
                        lines.add("-e=" + artifact.path().toAbsolutePath());
                    }

                    var libraryListFile = environment.getOutputPath("output");
                    Files.write(libraryListFile, lines);
                });
            }
            case "inject" -> {
                builder.output("output", NodeOutputType.JAR, "Source zip file containing additional NeoForm sources and resources");
                builder.action(environment -> {
                    var inputJar = environment.getRequiredInputPath("input");
                    var outputJar = environment.getOutputPath("output");

                    var injectSourceFolder = config.getDataPathInZip("inject");
                    if (injectSourceFolder == null) {
                        Files.copy(inputJar, outputJar);
                    } else {
                        new InjectZipContentTask(inputJar, outputJar, List.of(
                                new InjectFromZipSource(archive, injectSourceFolder)
                        )).run();
                    }
                });
            }
            case "patch" -> {
                var patchesInZip = Objects.requireNonNull(config.getDataPathInZip("patches"), "patches");
                builder.output("output", NodeOutputType.ZIP, "ZIP file containing the patched sources");
                builder.output("outputRejects", NodeOutputType.ZIP, "ZIP file containing the rejected patches");
                builder.action(environment -> {
                    var function = new NeoFormFunction(
                            "codechicken:DiffPatch:1.5.0.29:all",
                            null,
                            List.of(
                                    "{input}", archive.getName(),
                                    "--prefix", patchesInZip,
                                    "--patch",
                                    "--archive", "ZIP",
                                    "--output", "{output}",
                                    "--log-level", "WARN",
                                    "--mode", "OFFSET",
                                    "--archive-rejects", "ZIP",
                                    "--reject", "{outputRejects}"
                            ),
                            List.of()
                    );
                    runFunction(environment, step, function);
                });
            }
            default -> {
                var function = config.getFunction(step.type());
                if (function == null) {
                    throw new IllegalArgumentException("Step " + step.getId() + " references undefined function " + step.type());
                }

                applyFunctionToNode(step, function, builder);
            }
        }

        builder.build();

    }

    private void applyFunctionToNode(NeoFormStep step, NeoFormFunction function, ExecutionNodeBuilder builder) {
        // Collect referenced variables
        Set<String> functionVariables = new HashSet<>();
        for (var arg : function.args()) {
            NeoFormInterpolator.collectReferencedVariables(arg, functionVariables);
        }
        for (var arg : function.jvmargs()) {
            NeoFormInterpolator.collectReferencedVariables(arg, functionVariables);
        }

        // Resolve these function variables against the step
        for (var functionVariable : functionVariables) {
            if ("output".equals(functionVariable)) {
                var type = switch (step.type()) {
                    case "mergeMappings" -> NodeOutputType.TSRG;
                    default -> NodeOutputType.JAR;
                };
                builder.output(functionVariable, type, "Output of step " + step.type());
                continue;
            }

            var definition = step.values().get(functionVariable);
            if (definition == null) {
                // It's legal to also reference data *directly* from the function -> blergh
                if (config.hasData(functionVariable)) {
                    continue;
                }

                throw new IllegalArgumentException("Step " + step + " function referenced undefined variable " + functionVariable);
            }
        }

        builder.action(environment -> runFunction(environment, step, function));
    }

    private void createDownloadFromVersionManifest(ExecutionNodeBuilder builder, String manifestEntry, NodeOutputType jar, String description) {
        builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
        builder.output("output", jar, description);
        builder.action(environment -> {
            var versionManifest = environment.getRequiredInput("versionManifest", MinecraftVersionManifest.class);
            var result = artifactManager.downloadFromManifest(versionManifest, manifestEntry);
            environment.setOutput("output", result.path());
        });
    }

    private void triggerAndWait(Collection<ExecutionNode> nodes) throws InterruptedException {
        record Pair(ExecutionNode node, CompletableFuture<Void> future) {
        }
        var pairs = nodes.stream().map(node -> new Pair(node, getWaitCondition(node))).toList();
        for (var pair : pairs) {
            try {
                pair.future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                } else {
                    throw new NodeExecutionException(pair.node, e.getCause());
                }
            }
        }
    }

    private synchronized CompletableFuture<Void> getWaitCondition(ExecutionNode node) {
        var future = executingNodes.get(node);
        if (future == null) {
            future = CompletableFuture.runAsync(() -> {
                try {
                    runNode(node);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor);
            executingNodes.put(node, future);
        }
        return future;
    }

    private void runNode(ExecutionNode node) throws InterruptedException {
        // Wait for pre-requisites
        Set<ExecutionNode> dependencies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var input : node.inputs().values()) {
            dependencies.addAll(input.getNodeDependencies());
        }
        triggerAndWait(dependencies);

        node.start();

        try {
            var outputValues = new HashMap<String, Object>();
            var workspace = processingStepManager.createWorkspace(node.id());
            node.action().run(new ProcessingEnvironment() {
                @Override
                public Path getWorkspace() {
                    return workspace;
                }

                @Override
                public <T> T getRequiredInput(String id, Class<T> resultClass) {
                    return node.getRequiredInput(id).resolve(resultClass);
                }

                @Override
                public Path getOutputPath(String id) {
                    var output = node.getRequiredOutput(id);
                    var filename = id + "." + output.type().name().toLowerCase(Locale.ROOT);
                    var path = workspace.resolve(filename);
                    setOutput(id, path);
                    return path;
                }

                @Override
                public void setOutput(String id, Object result) {
                    var output = node.getRequiredOutput(id);
                    if (outputValues.containsKey(id)) {
                        throw new IllegalStateException("Path for node output " + id + " is already set.");
                    }
                    if (!output.type().isValidResult(result)) {
                        throw new IllegalArgumentException("Given result " + result + " is not valid for output type " + output.type());
                    }
                    outputValues.put(id, result);
                }
            });
            node.complete(outputValues);
        } catch (Throwable t) {
            node.fail();
            throw new NodeExecutionException(node, t);
        }

    }

    private void runFunction(ProcessingEnvironment environment, NeoFormStep step, NeoFormFunction function) throws IOException, InterruptedException {
        var interpolator = new NeoFormInterpolator(environment, step, config, archive);

        Artifact toolArtifact;
        if (function.repository() != null) {
            toolArtifact = artifactManager.get(function.toolArtifact(), function.repository());
        } else {
            toolArtifact = artifactManager.get(function.toolArtifact());
        }

        var javaExecutablePath = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow();

        var workingDir = environment.getWorkspace();
        var toolPath = workingDir.relativize(toolArtifact.path());

        var command = new ArrayList<String>();
        command.add(javaExecutablePath);

        // JVM
        for (var jvmArg : function.jvmargs()) {
            command.add(interpolator.interpolate(jvmArg));
        }

        command.add("-jar");
        command.add(toolPath.toString());

        // Program Arguments
        for (var arg : function.args()) {
            // For specific tasks we "fixup" the neoform spec
            if (function.toolArtifact().startsWith("org.vineflower:vineflower:")) {
                arg = arg.replace("TRACE", "WARN");
            }

            command.add(interpolator.interpolate(arg));
        }

        System.out.println("Running external tool " + function.toolArtifact());
        System.out.println(String.join(" ", command));

        var process = new ProcessBuilder()
                .directory(workingDir.toFile())
                .command(command)
                .redirectErrorStream(true)
                .redirectOutput(workingDir.resolve("console_output.txt").toFile())
                .start();

        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to execute tool");
        }

    }
}

