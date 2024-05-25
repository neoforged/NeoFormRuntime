package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.actions.CreateLibrariesOptionsFileAction;
import net.neoforged.neoform.runtime.actions.DownloadFromVersionManifestAction;
import net.neoforged.neoform.runtime.actions.DownloadLauncherManifestAction;
import net.neoforged.neoform.runtime.actions.DownloadVersionManifestAction;
import net.neoforged.neoform.runtime.actions.ExternalJavaToolAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.actions.RecompileSourcesActionWithECJ;
import net.neoforged.neoform.runtime.actions.RecompileSourcesActionWithJDK;
import net.neoforged.neoform.runtime.actions.SplitResourcesFromClassesAction;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormDistConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormFunction;
import net.neoforged.neoform.runtime.config.neoform.NeoFormStep;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeExecutionException;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import net.neoforged.neoform.runtime.graph.transforms.GraphTransform;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.LoggerCategory;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.StringUtil;
import net.neoforged.neoform.runtime.cli.LockManager;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.zip.ZipFile;

public class NeoFormEngine implements AutoCloseable {
    private static final Logger LOG = Logger.create(LoggerCategory.EXECUTION);

    private final ArtifactManager artifactManager;
    private final FileHashService fileHashService;
    private final CacheManager cacheManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ExecutionNode, CompletableFuture<Void>> executingNodes = new IdentityHashMap<>();
    private final LockManager lockManager;
    private final ExecutionGraph graph = new ExecutionGraph();
    private final BuildOptions buildOptions = new BuildOptions();
    private boolean verbose;

    /**
     * Nodes can reference certain configuration data (access transformers, patches, etc.) which come
     * from external sources. This map maintains the id -> location mapping to find this data.
     */
    private Map<String, DataSource> dataSources = new HashMap<>();

    public NeoFormEngine(ArtifactManager artifactManager,
                         FileHashService fileHashService,
                         CacheManager cacheManager,
                         LockManager lockManager) {
        this.artifactManager = artifactManager;
        this.fileHashService = fileHashService;
        this.cacheManager = cacheManager;
        this.lockManager = lockManager;
    }

    public void close() throws IOException {
        for (var location : dataSources.values()) {
            location.archive().close();
        }
        executor.close();
    }

    public void addDataSource(String id, ZipFile zipFile, String sourceFolder) {
        if (dataSources.containsKey(id)) {
            throw new IllegalArgumentException("Data source " + id + " is already defined");
        }
        dataSources.put(id, new DataSource(zipFile, sourceFolder));
    }

    public void loadNeoFormData(MavenCoordinate neoFormArtifactId, String dist) throws IOException {
        var neoFormArchive = artifactManager.get(Objects.requireNonNull(neoFormArtifactId, "neoFormArtifactId"));
        var zipFile = new ZipFile(neoFormArchive.path().toFile());
        var config = NeoFormConfig.from(zipFile);
        var distConfig = config.getDistConfig(dist);

        // Add the data sources defined in the NeoForm config file
        for (var entry : distConfig.getData().entrySet()) {
            addDataSource(entry.getKey(), zipFile, entry.getValue());
        }

        loadNeoFormProcess(distConfig);
    }

    public void loadNeoFormProcess(NeoFormDistConfig distConfig) {
        for (var step : distConfig.steps()) {
            addNodeForStep(graph, distConfig, step);
        }

        var sourcesOutput = graph.getRequiredOutput("patch", "output");

        // Add a recompile step
        var builder = graph.nodeBuilder("recompile");
        builder.input("sources", sourcesOutput.asInput());
        builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
        var compiledOutput = builder.output("output", NodeOutputType.JAR, "Compiled minecraft sources");
        RecompileSourcesAction compileAction;
        if (buildOptions.isUseEclipseCompiler()) {
            compileAction = new RecompileSourcesActionWithECJ();
        } else {
            compileAction = new RecompileSourcesActionWithJDK();
        }

        // Add NeoForm libraries or apply overridden classpath fully
        compileAction.getClasspath().setOverriddenClasspath(buildOptions.getOverriddenCompileClasspath());
        compileAction.getClasspath().addMavenLibraries(distConfig.libraries());
        builder.action(compileAction);
        builder.build();

        // Register the sources and the compiled binary as results
        graph.setResult("sources", sourcesOutput);
        graph.setResult("compiled", compiledOutput);

        // The split-off resources must also be made available. The steps are not consistently named across dists
        if (graph.hasOutput("stripClient", "resourcesOutput")) {
            graph.setResult("clientResources", graph.getRequiredOutput("stripClient", "resourcesOutput"));
        }
        if (graph.hasOutput("stripServer", "resourcesOutput")) {
            graph.setResult("serverResources", graph.getRequiredOutput("stripServer", "resourcesOutput"));
        }
        if (graph.hasOutput("strip", "resourcesOutput")) {
            graph.setResult("resources", graph.getRequiredOutput("strip", "resourcesOutput"));
        }
    }

    private void addNodeForStep(ExecutionGraph graph, NeoFormDistConfig config, NeoFormStep step) {
        var builder = graph.nodeBuilder(step.getId());

        // "variables" should now hold all global variables referenced by the step/function, but those
        //  might still either reference the outputs of other nodes, or entries in the data dictionary.
        for (var entry : step.values().entrySet()) {
            var variables = new HashSet<String>();
            NeoFormInterpolator.collectReferencedVariables(entry.getValue(), variables);

            for (String variable : variables) {
                var resolvedOutput = graph.getOutput(variable);
                if (resolvedOutput == null) {
                    if (dataSources.containsKey(variable)) {
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
                builder.output("output", NodeOutputType.JAR, "The jar-file that contains only .class files");
                builder.output("resourcesOutput", NodeOutputType.JAR, "The jar-file that contains anything but .class files");
                var action = new SplitResourcesFromClassesAction();
                // The Minecraft jar contains nothing of interest in META-INF, and the signature files are useless.
                action.addDenyPatterns("META-INF/.*");
                builder.action(action);
            }
            case "listLibraries" -> {
                builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
                builder.output("output", NodeOutputType.TXT, "A list of all external JAR files needed to decompile/recompile");
                var action = new CreateLibrariesOptionsFileAction();
                action.getClasspath().setOverriddenClasspath(buildOptions.getOverriddenCompileClasspath());
                action.getClasspath().addMavenLibraries(config.libraries());
                builder.action(action);
            }
            case "inject" -> {
                var injectionSource = getRequiredDataSource("inject");

                builder.output("output", NodeOutputType.JAR, "Source zip file containing additional NeoForm sources and resources");
                builder.action(new InjectZipContentAction(
                        List.of(new InjectFromZipFileSource(injectionSource.archive(), injectionSource.folder()))
                ));
            }
            case "patch" -> {
                var patchSource = getRequiredDataSource("patches");

                builder.clearInputs();
                PatchActionFactory.makeAction(
                        builder,
                        Paths.get(patchSource.archive().getName()),
                        config.getDataPathInZip("patches"),
                        graph.getRequiredOutput("inject", "output")
                );
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

    private DataSource getRequiredDataSource(String dataId) {
        var result = dataSources.get(dataId);
        if (result == null) {
            throw new IllegalArgumentException("Required data source " + dataId + " not found");
        }
        return result;
    }

    private void applyFunctionToNode(NeoFormStep step, NeoFormFunction function, ExecutionNodeBuilder builder) {
        var resolvedJvmArgs = new ArrayList<>(Objects.requireNonNullElse(function.jvmargs(), List.of()));
        var resolvedArgs = new ArrayList<>(Objects.requireNonNullElse(function.args(), List.of()));

        // Start by resolving the function->step indirection where functions can reference variables that
        // are defined in the step. Usually (but not always) these will just refer to further global variables.
        for (var entry : step.values().entrySet()) {
            UnaryOperator<String> resolver = s -> s.replace("{" + entry.getKey() + "}", entry.getValue());
            resolvedJvmArgs.replaceAll(resolver);
            resolvedArgs.replaceAll(resolver);
        }

        // Now resolve the remaining placeholders.
        Consumer<String> placeholderProcessor = text -> {
            var matcher = NeoFormInterpolator.TOKEN_PATTERN.matcher(text);
            while (matcher.find()) {
                var variable = matcher.group(1);

                // Handle the "magic" output variable. In NeoForm JSON, it's impossible to know which
                // variables are truly intended to be outputs.
                if ("output".equals(variable)) {
                    var type = switch (step.type()) {
                        case "mergeMappings" -> NodeOutputType.TSRG;
                        default -> NodeOutputType.JAR;
                    };
                    if (!builder.hasOutput(variable)) {
                        builder.output(variable, type, "Output of step " + step.type());
                    }
                } else if (dataSources.containsKey(variable)) {
                    // It likely refers to data from the NeoForm zip, this will be handled by the runtime later
                } else if (variable.endsWith("Output")) {
                    // The only remaining supported variable form is referencing outputs of other steps
                    // this is done via <stepName>Output.
                    var otherStep = variable.substring(0, variable.length() - "Output".length());
                    builder.inputFromNodeOutput(variable, otherStep, "output");
                } else if (variable.equals("log")) {
                    // This variable is used in legacy MCP config JSONs to signify the path to a logfile and is ignored here
                } else {
                    throw new IllegalArgumentException("Unsupported variable " + variable + " used by step " + step.getId());
                }
            }
        };
        resolvedJvmArgs.forEach(placeholderProcessor);
        resolvedArgs.forEach(placeholderProcessor);

        MavenCoordinate toolArtifactCoordinate;
        try {
            toolArtifactCoordinate = MavenCoordinate.parse(function.toolArtifact());
        } catch (Exception e) {
            throw new IllegalArgumentException("Function for step " + step + " has invalid tool: " + function.toolArtifact());
        }

        var action = new ExternalJavaToolAction(toolArtifactCoordinate);
        action.setRepositoryUrl(function.repository());
        action.setJvmArgs(resolvedJvmArgs);
        action.setArgs(resolvedArgs);
        builder.action(action);
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
                var originalName = Thread.currentThread().getName();
                try {
                    Thread.currentThread().setName("run-" + node.id());
                    runNode(node);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    Thread.currentThread().setName(originalName);
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
        var ck = new CacheKeyBuilder(node.id(), fileHashService);
        for (var entry : node.inputs().entrySet()) {
            entry.getValue().collectCacheKeyComponent(ck);
        }
        node.action().computeCacheKey(ck);

        node.start();
        var cacheKey = ck.build();
        if (verbose) {
            LOG.println(" Cache Key: " + cacheKey);
            LOG.println(AnsiColor.BLACK_BRIGHT + StringUtil.indent(cacheKey.describe(), 2) + AnsiColor.RESET);
        }

        try (var lock = lockManager.lock(cacheKey.toString())) {
            var outputValues = new HashMap<String, Path>();

            if (cacheManager.restoreOutputsFromCache(node, cacheKey, outputValues)) {
                node.complete(outputValues, true);
                return;
            }

            var workspace = cacheManager.createWorkspace(node.id());
            node.action().run(new ProcessingEnvironment() {
                @Override
                public ArtifactManager getArtifactManager() {
                    return artifactManager;
                }

                @Override
                public Path getWorkspace() {
                    return workspace;
                }

                @Override
                public String interpolateString(String text) throws IOException {
                    var matcher = NeoFormInterpolator.TOKEN_PATTERN.matcher(text);

                    var result = new StringBuilder();
                    while (matcher.find()) {
                        var variableValue = getVariableValue(matcher.group(1));
                        var replacement = Matcher.quoteReplacement(variableValue);
                        matcher.appendReplacement(result, replacement);
                    }
                    matcher.appendTail(result);

                    return result.toString();
                }

                private String getVariableValue(String variable) throws IOException {
                    Path resultPath; // All results are paths

                    var nodeInput = node.inputs().get(variable);
                    if (nodeInput != null) {
                        resultPath = nodeInput.getValue(ResultRepresentation.PATH);
                    } else if (node.outputs().containsKey(variable)) {
                        resultPath = getOutputPath(variable);
                    } else if (dataSources.containsKey(variable)) {
                        // We can also access data-files defined in the NeoForm archive via the `data` indirection
                        resultPath = extractData(variable);
                    } else if ("log".equals(variable)) {
                        // Old MCP versions support "log" to point to a path
                        resultPath = workspace.resolve("log.txt");
                    } else {
                        throw new IllegalArgumentException("Variable " + variable + " is neither an input, output or configuration data");
                    }

                    return representPath(resultPath);
                }

                public Path extractData(String dataId) throws IOException {
                    var dataSource = dataSources.get(dataId);
                    var archive = dataSource.archive();
                    var dataPath = dataSource.folder();
                    var rootEntry = archive.getEntry(dataPath);
                    if (rootEntry == null) {
                        throw new IllegalArgumentException("NeoForm archive entry " + dataPath + " does not exist in " + archive.getName() + ".");
                    }

                    if (rootEntry.getName().startsWith("/") || rootEntry.getName().contains("..")) {
                        throw new IllegalArgumentException("Unsafe ZIP path: " + rootEntry.getName());
                    }

                    // Determine if an entire directory or only a file needs to be extracted
                    if (rootEntry.isDirectory()) {
                        var targetDirPath = workspace.resolve(rootEntry.getName());
                        if (!Files.exists(targetDirPath)) {
                            try {
                                Files.createDirectories(targetDirPath);
                                var entryIter = archive.entries().asIterator();
                                while (entryIter.hasNext()) {
                                    var entry = entryIter.next();
                                    if (!entry.isDirectory() && entry.getName().startsWith(rootEntry.getName())) {
                                        var relativePath = entry.getName().substring(rootEntry.getName().length());
                                        var targetPath = targetDirPath.resolve(relativePath).normalize();
                                        if (!targetPath.startsWith(targetDirPath)) {
                                            throw new IllegalArgumentException("Directory escape: " + targetPath);
                                        }
                                        Files.createDirectories(targetPath.getParent());

                                        try (var in = archive.getInputStream(entry)) {
                                            Files.copy(in, targetPath);
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to extract referenced NeoForm data " + dataPath + " to " + targetDirPath, e);
                            }
                        }
                        return targetDirPath;
                    } else {
                        var path = workspace.resolve(rootEntry.getName());
                        if (!Files.exists(path)) {
                            try {
                                Files.createDirectories(path.getParent());
                                try (var in = archive.getInputStream(rootEntry)) {
                                    Files.copy(in, path);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to extract referenced NeoForm data " + dataPath + " to " + path, e);
                            }
                        }
                        return path;
                    }
                }

                private String representPath(Path path) {
                    var result = workspace.relativize(path);
                    if (result.getParent() == null) {
                        // Some tooling can't deal with paths that do not have directories
                        return "./" + result;
                    } else {
                        return result.toString();
                    }
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

                @Override
                public boolean isVerbose() {
                    return verbose;
                }
            });

            // Only cache if all outputs are in the workdir, otherwise
            // we assume some of them are artifacts and will always come from the
            // artifact cache
            if (outputValues.values().stream().allMatch(p -> p.startsWith(workspace))) {
                cacheManager.saveOutputs(node, cacheKey, outputValues);
            }
            node.complete(outputValues, false);
        } catch (Throwable t) {
            node.fail();
            throw new NodeExecutionException(node, t);
        }
    }

    public ArtifactManager getArtifactManager() {
        return artifactManager;
    }

    public Set<String> getAvailableResults() {
        return graph.getResults().keySet();
    }

    public Map<String, Path> createResults(String... ids) throws InterruptedException {
        // Determine the nodes we need to run
        Set<ExecutionNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : ids) {
            var nodeOutput = graph.getResult(id);
            if (nodeOutput == null) {
                throw new IllegalArgumentException("Unknown result: " + id + ". Available results: " + getAvailableResults());
            }
            nodes.add(nodeOutput.getNode());
        }

        triggerAndWait(nodes);

        // Collect results paths
        var results = new HashMap<String, Path>();
        for (String id : ids) {
            var nodeOutput = graph.getResult(id);
            results.put(id, nodeOutput.getResultPath());
            // TODO: move to actual result cache
        }
        return results;
    }

    public void dumpGraph(PrintWriter printWriter) {
        graph.dump(printWriter);
    }

    public void applyTransforms(List<GraphTransform> transforms) {
        for (GraphTransform transform : transforms) {
            transform.apply(this, graph);
        }
    }

    public ExecutionGraph getGraph() {
        return graph;
    }

    public BuildOptions getBuildOptions() {
        return buildOptions;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }
}
