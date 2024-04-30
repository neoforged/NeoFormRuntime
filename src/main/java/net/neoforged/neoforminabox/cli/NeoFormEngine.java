package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.actions.CreateLibrariesOptionsFileAction;
import net.neoforged.neoforminabox.actions.DownloadFromVersionManifestAction;
import net.neoforged.neoforminabox.actions.DownloadLauncherManifestAction;
import net.neoforged.neoforminabox.actions.DownloadVersionManifestAction;
import net.neoforged.neoforminabox.actions.ExternalToolAction;
import net.neoforged.neoforminabox.actions.FilterJarContentAction;
import net.neoforged.neoforminabox.actions.InjectFromZipSource;
import net.neoforged.neoforminabox.actions.InjectZipContentAction;
import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.config.neoform.NeoFormConfig;
import net.neoforged.neoforminabox.config.neoform.NeoFormDistConfig;
import net.neoforged.neoforminabox.config.neoform.NeoFormFunction;
import net.neoforged.neoforminabox.config.neoform.NeoFormStep;
import net.neoforged.neoforminabox.graph.ExecutionGraph;
import net.neoforged.neoforminabox.graph.ExecutionNode;
import net.neoforged.neoforminabox.graph.ExecutionNodeBuilder;
import net.neoforged.neoforminabox.graph.NodeExecutionException;
import net.neoforged.neoforminabox.graph.NodeOutputType;
import net.neoforged.neoforminabox.graph.ResultRepresentation;
import net.neoforged.neoforminabox.utils.HashingUtil;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

public class NeoFormEngine implements AutoCloseable {
    private final ArtifactManager artifactManager;
    private final FileHashService fileHashService;
    private final CacheManager cacheManager;
    private final ProcessingStepManager processingStepManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ExecutionNode, CompletableFuture<Void>> executingNodes = new IdentityHashMap<>();
    private final ZipFile archive;
    private final NeoFormDistConfig config;
    private final LockManager lockManager;

    private NeoFormEngine(ArtifactManager artifactManager,
                          FileHashService fileHashService,
                          CacheManager cacheManager,
                          ProcessingStepManager processingStepManager,
                          LockManager lockManager,
                          ZipFile archive,
                          NeoFormDistConfig config) {
        this.artifactManager = artifactManager;
        this.fileHashService = fileHashService;
        this.processingStepManager = processingStepManager;
        this.archive = archive;
        this.config = config;
        this.cacheManager = cacheManager;
        this.lockManager = lockManager;
    }

    public void close() throws IOException {
        archive.close();
    }

    public static NeoFormEngine create(ArtifactManager artifactManager,
                                       FileHashService fileHashService,
                                       CacheManager cacheManager,
                                       ProcessingStepManager processingStepManager,
                                       LockManager lockManager,
                                       String neoFormArtifactId,
                                       String dist) throws IOException {
        var neoFormArchive = artifactManager.get(neoFormArtifactId);
        var zipFile = new ZipFile(neoFormArchive.path().toFile());
        var neoformConfig = NeoFormConfig.from(zipFile);
        var distConfig = neoformConfig.getDistConfig(dist);
        return new NeoFormEngine(artifactManager, fileHashService, cacheManager, processingStepManager, lockManager, zipFile, distConfig);
    }

    public ExecutionGraph buildGraph() {
        var graph = new ExecutionGraph();

        for (var step : config.steps()) {
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
                builder.action(new DownloadLauncherManifestAction(artifactManager));
            }
            case "downloadJson" -> {
                builder.output("output", NodeOutputType.JSON, "Version manifest for a particular Minecraft version");
                builder.action(new DownloadVersionManifestAction(artifactManager, config));
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
                builder.action(new FilterJarContentAction());
            }
            case "listLibraries" -> {
                builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
                builder.output("output", NodeOutputType.TXT, "A list of all external JAR files needed to decompile/recompile");
                builder.action(new CreateLibrariesOptionsFileAction(artifactManager, config));
            }
            case "inject" -> {
                var injectedFolder = config.getDataPathInZip("inject");

                builder.output("output", NodeOutputType.JAR, "Source zip file containing additional NeoForm sources and resources");
                builder.action(new InjectZipContentAction(
                        List.of(new InjectFromZipSource(archive, injectedFolder))
                ));
            }
            case "patch" -> {
                var patchesInZip = Objects.requireNonNull(config.getDataPathInZip("patches"), "patches");
                builder.output("output", NodeOutputType.ZIP, "ZIP file containing the patched sources");
                builder.output("outputRejects", NodeOutputType.ZIP, "ZIP file containing the rejected patches");
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
                builder.action(new ExternalToolAction(artifactManager, step, function, config, archive));
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

        builder.action(new ExternalToolAction(artifactManager, step, function, config, archive));
    }

    private void createDownloadFromVersionManifest(ExecutionNodeBuilder builder, String manifestEntry, NodeOutputType jar, String description) {
        builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
        builder.output("output", jar, description);
        builder.action(new DownloadFromVersionManifestAction(artifactManager, manifestEntry));
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

    public void runNode(ExecutionNode node) throws InterruptedException {
        // Wait for pre-requisites
        Set<ExecutionNode> dependencies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var input : node.inputs().values()) {
            dependencies.addAll(input.getNodeDependencies());
        }
        triggerAndWait(dependencies);

        // Prep node output cache
        var ck = new CacheKeyBuilder(fileHashService);
        for (var entry : node.inputs().entrySet()) {
            entry.getValue().collectCacheKeyComponent(ck);
        }
        node.action().computeCacheKey(ck);

        node.start();
        var cacheKeyDescription = ck.buildCacheKey();
        var cacheKey = node.id() + "_" + HashingUtil.sha1(ck.buildCacheKey());

        try (var lock = lockManager.lock(cacheKey)) {
            var outputValues = new HashMap<String, Path>();

            var intermediateCacheDir = cacheManager.getCacheDir().resolve("intermediate_results");
            Files.createDirectories(intermediateCacheDir);
            var cacheMarkerFile = intermediateCacheDir.resolve(cacheKey + ".txt");
            if (Files.isRegularFile(cacheMarkerFile)) {
                // Try to rebuild output values from cache
                boolean complete = true;
                for (var entry : node.outputs().entrySet()) {
                    var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
                    var cachedFile = intermediateCacheDir.resolve(filename);
                    if (Files.isRegularFile(cachedFile)) {
                        outputValues.put(entry.getKey(), cachedFile);
                    } else {
                        System.err.println("Cache for " + node.id() + " is incomplete. Missing: " + filename);
                        outputValues.clear();
                        complete = false;
                        break;
                    }
                }
                if (complete) {
                    node.complete(outputValues);
                    return;
                }
            }

            var workspace = processingStepManager.createWorkspace(node.id());
            node.action().run(new ProcessingEnvironment() {
                @Override
                public Path getWorkspace() {
                    return workspace;
                }

                @Override
                public <T> T getRequiredInput(String id, ResultRepresentation<T> representation) throws IOException {
                    return node.getRequiredInput(id).getValue(representation);
                }

                @Override
                public Path getOutputPath(String id) {
                    var output = node.getRequiredOutput(id);
                    var filename = id + output.type().getExtension();
                    var path = workspace.resolve(filename);
                    setOutput(id, path);
                    return path;
                }

                @Override
                public void setOutput(String id, Path resultPath) {
                    node.getRequiredOutput(id); // This will throw if id is unknown
                    if (outputValues.containsKey(id)) {
                        throw new IllegalStateException("Path for node output " + id + " is already set.");
                    }
                    outputValues.put(id, resultPath);
                }
            });

            // Only cache if all outputs are in the workdir, otherwise
            // we assume some of them are artifacts and will always come from the
            // artifact cache
            if (outputValues.values().stream().allMatch(p -> p.startsWith(workspace))) {
                System.out.println("Caching outputs...");
                var finalOutputValues = new HashMap<String, Path>(outputValues.size());
                for (var entry : outputValues.entrySet()) {
                    var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
                    var cachedPath = intermediateCacheDir.resolve(filename);
                    try {
                        Files.move(entry.getValue(), cachedPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(entry.getValue(), cachedPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    finalOutputValues.put(entry.getKey(), cachedPath);
                }
                Files.writeString(cacheMarkerFile, cacheKeyDescription);

                node.complete(finalOutputValues);
            } else {
                node.complete(outputValues);
            }
        } catch (Throwable t) {
            node.fail();
            throw new NodeExecutionException(node, t);
        }
    }
}

