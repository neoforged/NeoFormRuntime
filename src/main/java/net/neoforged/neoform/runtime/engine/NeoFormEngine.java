package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.actions.CreateLegacyMappingsAction;
import net.neoforged.neoform.runtime.actions.CreateLibrariesOptionsFile;
import net.neoforged.neoform.runtime.actions.DownloadFromVersionManifestAction;
import net.neoforged.neoform.runtime.actions.DownloadLauncherManifestAction;
import net.neoforged.neoform.runtime.actions.DownloadVersionManifestAction;
import net.neoforged.neoform.runtime.actions.ExternalJavaToolAction;
import net.neoforged.neoform.runtime.actions.GenerateMCPSrgFilesAction;
import net.neoforged.neoform.runtime.actions.InjectFromZipFileSource;
import net.neoforged.neoform.runtime.actions.InjectZipContentAction;
import net.neoforged.neoform.runtime.actions.MergeWithSourcesAction;
import net.neoforged.neoform.runtime.actions.PatchActionFactory;
import net.neoforged.neoform.runtime.actions.RecompileSourcesAction;
import net.neoforged.neoform.runtime.actions.RecompileSourcesActionWithECJ;
import net.neoforged.neoform.runtime.actions.RecompileSourcesActionWithJDK;
import net.neoforged.neoform.runtime.actions.RemapSrgClassesAction;
import net.neoforged.neoform.runtime.actions.RemapSrgClassesToMcpAction;
import net.neoforged.neoform.runtime.actions.RemapSrgSourcesAction;
import net.neoforged.neoform.runtime.actions.RemapSrgSourcesToMcpAction;
import net.neoforged.neoform.runtime.actions.SplitResourcesFromClassesAction;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.cli.LockManager;
import net.neoforged.neoform.runtime.cli.ResultIds;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormDistConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormFunction;
import net.neoforged.neoform.runtime.config.neoform.NeoFormStep;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeExecutionException;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import net.neoforged.neoform.runtime.graph.transforms.GraphTransform;
import net.neoforged.neoform.runtime.graph.transforms.ReplaceNodeOutput;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.JavaInstallationInformation;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.OsUtil;
import net.neoforged.neoform.runtime.utils.StringUtil;
import net.neoforged.problems.ProblemReporter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Logger LOG = Logger.create();

    private final ArtifactManager artifactManager;
    private final FileHashService fileHashService;
    private final CacheManager cacheManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ExecutionNode, CompletableFuture<Void>> executingNodes = new IdentityHashMap<>();
    private final LockManager lockManager;
    private final ExecutionGraph graph = new ExecutionGraph();
    private final BuildOptions buildOptions = new BuildOptions();
    private boolean verbose;
    private ProcessGeneration processGeneration;
    private Path mcpMappingsData;

    /**
     * Nodes can reference certain configuration data (access transformers, patches, etc.) which come
     * from external sources. This map maintains the id -> location mapping to find this data.
     */
    private final Map<String, DataSource> dataSources = new HashMap<>();

    /**
     * Resources owned by the engine which will be closed when the engine closes.
     */
    private final List<AutoCloseable> managedResources = new ArrayList<>();

    /**
     * The current problem reporter.
     */
    private ProblemReporter problemReporter = ProblemReporter.NOOP;

    /**
     * The path to the java executable for running external tools.
     */
    private String javaExecutable;

    @Nullable
    private JavaInstallationInformation javaExecutableInformation;

    public NeoFormEngine(ArtifactManager artifactManager,
                         FileHashService fileHashService,
                         CacheManager cacheManager,
                         LockManager lockManager) {
        this.artifactManager = artifactManager;
        this.fileHashService = fileHashService;
        this.cacheManager = cacheManager;
        this.lockManager = lockManager;

        this.javaExecutable = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow();
        this.javaExecutableInformation = JavaInstallationInformation.fromRunningJVM();
    }

    public void close() throws IOException {
        List<Exception> suppressedExceptions = new ArrayList<>();
        for (var location : dataSources.values()) {
            try {
                location.archive().close();
            } catch (Exception e) {
                suppressedExceptions.add(e);
            }
        }
        for (var resource : managedResources) {
            try {
                resource.close();
            } catch (Exception e) {
                suppressedExceptions.add(e);
            }
        }
        try {
            executor.close();
        } catch (Exception e) {
            suppressedExceptions.add(e);
        }
        if (!suppressedExceptions.isEmpty()) {
            var e = new IOException("Failed to close one or more resources.");
            for (var suppressedException : suppressedExceptions) {
                e.addSuppressed(suppressedException);
            }
            throw e;
        }
    }

    /**
     * Adds a resource to be closed when the engine closes.
     */
    public <T extends AutoCloseable> T addManagedResource(T resource) {
        managedResources.add(resource);
        return resource;
    }

    public void addDataSource(String id, ZipFile zipFile, String sourceFolder) {
        if (dataSources.containsKey(id)) {
            throw new IllegalArgumentException("Data source " + id + " is already defined");
        }
        dataSources.put(id, new DataSource(zipFile, sourceFolder, fileHashService));
    }

    public void loadNeoFormData(Path neoFormDataPath, String dist) throws IOException {
        var zipFile = new ZipFile(neoFormDataPath.toFile());
        var config = NeoFormConfig.from(zipFile);
        var distConfig = config.getDistConfig(dist);

        // Add the data sources defined in the NeoForm config file
        for (var entry : distConfig.getData().entrySet()) {
            addDataSource(entry.getKey(), zipFile, entry.getValue());
        }

        loadNeoFormProcess(distConfig);
    }

    public void loadNeoFormProcess(NeoFormDistConfig distConfig) {
        processGeneration = ProcessGeneration.fromMinecraftVersion(distConfig.minecraftVersion());

        for (var step : distConfig.steps()) {
            if (step.name().equals("listLibraries")) {
                // We fold listLibraries inside the steps that use it
                continue;
            }
            addNodeForStep(graph, distConfig, step);
        }

        var sourcesOutput = graph.getRequiredOutput("patch", "output");

        var compiledOutput = addRecompileStep(distConfig, sourcesOutput);

        var sourcesAndCompiledOutput = addMergeWithSourcesStep(compiledOutput, sourcesOutput);

        // Register the sources and the compiled binary as results
        // Vanilla deobfuscated is equivalent to the input to the decompiler at this point of setting up the process.
        // While the input to the decompiler might be adjusted later, that will not change this result retroactively.
        var decompileInput = graph.getRequiredInput("decompile", "input");
        graph.setResultFromCurrentInput(ResultIds.VANILLA_DEOBFUSCATED, decompileInput);

        graph.setResult(ResultIds.GAME_SOURCES, sourcesOutput);
        graph.setResult(ResultIds.GAME_JAR, compiledOutput);
        graph.setResult(ResultIds.GAME_JAR_WITH_SOURCES, sourcesAndCompiledOutput);

        // The split-off resources must also be made available. The steps are not consistently named across dists
        if (graph.hasOutput("stripClient", "resourcesOutput")) {
            graph.setResult(ResultIds.CLIENT_RESOURCES, graph.getRequiredOutput("stripClient", "resourcesOutput"));
        }
        if (graph.hasOutput("stripServer", "resourcesOutput")) {
            graph.setResult(ResultIds.SERVER_RESOURCES, graph.getRequiredOutput("stripServer", "resourcesOutput"));
        }
        if (graph.hasOutput("strip", "resourcesOutput")) {
            graph.setResult(ResultIds.GAME_RESOURCES, graph.getRequiredOutput("strip", "resourcesOutput"));
        }

        // If we're running NeoForm for 1.20.1 or earlier, the sources after patches use
        // SRG method and field names, and need to be remapped.
        if (processGeneration.sourcesUseIntermediaryNames()) {
            boolean hasMojmapSteps = graph.hasOutput("mergeMappings", "output") && graph.hasOutput("downloadClientMappings", "output");
            if (!hasMojmapSteps && !processGeneration.classesUseMCPNames()) {
                throw new IllegalStateException("The NeoForm/MCP process has no nodes for mergeMappings/downloadClientMappings!");
            }

            applyTransforms(List.of(
                    new ReplaceNodeOutput(
                            "patch",
                            "output",
                            "remapSrgSourcesToOfficial",
                            (builder, previousNodeOutput) -> {
                                builder.input("sources", previousNodeOutput.asInput());
                                RemapSrgSourcesAction action;
                                if (hasMojmapSteps) {
                                    builder.input("mergedMappings", graph.getRequiredOutput("mergeMappings", "output").asInput());
                                    builder.input("officialMappings", graph.getRequiredOutput("downloadClientMappings", "output").asInput());
                                    action = new RemapSrgSourcesAction();
                                } else {
                                    action = new RemapSrgSourcesToMcpAction(mcpMappingsData);
                                }
                                builder.action(action);
                                return builder.output("output", NodeOutputType.ZIP, "Sources with SRG method and field names remapped to official.");
                            }
                    )
            ));

            // We also expose a few results for mappings in different formats
            var createMappings = graph.nodeBuilder("createMappings");
            if (hasMojmapSteps) {
                createMappings.inputFromNodeOutput("officialToObf", "downloadClientMappings", "output");
                createMappings.inputFromNodeOutput("obfToSrg", "mergeMappings", "output");
                var action = new CreateLegacyMappingsAction();
                createMappings.action(action);
            } else {
                createMappings.action(new GenerateMCPSrgFilesAction(mcpMappingsData));
            }
            graph.setResult(ResultIds.NAMED_TO_INTERMEDIARY_MAPPING, createMappings.output("officialToSrg", NodeOutputType.TSRG, "A mapping file that maps user-facing (Mojang, MCP) names to intermediary (SRG)"));
            graph.setResult(ResultIds.INTERMEDIARY_TO_NAMED_MAPPING, createMappings.output("srgToOfficial", NodeOutputType.SRG, "A mapping file that maps intermediary (SRG) names to user-facing (Mojang, MCP) names"));
            graph.setResult(ResultIds.CSV_MAPPING, createMappings.output("csvMappings", NodeOutputType.ZIP, "A zip containing csv files with SRG to official mappings"));
            createMappings.build();

            // If intermediary is in use, the game jar has to be remapped to developer-facing names to be usable
            {
                var builder = graph.nodeBuilder("remapSrgClassesToOfficial");
                builder.input("input", decompileInput.copy());
                var officialOutput = builder.output("output", NodeOutputType.JAR, "Classes with SRG method and field names remapped to official.");
                if (hasMojmapSteps) {
                    builder.input("mergedMappings", graph.getRequiredOutput("mergeMappings", "output").asInput());
                    builder.input("officialMappings", graph.getRequiredOutput("downloadClientMappings", "output").asInput());
                    builder.action(new RemapSrgClassesAction());
                } else {
                    // We can provide the SRG->MCP mappings generated by the earlier task
                    builder.input("srgToMcpMappings", graph.getResult(ResultIds.INTERMEDIARY_TO_NAMED_MAPPING).asInput());
                    builder.action(new RemapSrgClassesToMcpAction(this.mcpMappingsData));
                }
                builder.build();

                graph.setResult(ResultIds.GAME_JAR_NO_RECOMP, officialOutput);
            }
        } else {
            // Without the presence of further patching or renaming, the game jar without recompilation is the deobfuscated vanilla jar
            graph.setResultFromCurrentInput(ResultIds.GAME_JAR_NO_RECOMP, decompileInput);
        }
    }

    private NodeOutput addRecompileStep(NeoFormDistConfig distConfig, NodeOutput sourcesOutput) {
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

        compileAction.setTargetJavaVersion(distConfig.javaVersion());

        // Add NeoForm libraries or apply overridden classpath fully
        compileAction.getClasspath().setOverriddenClasspath(buildOptions.getOverriddenCompileClasspath());
        compileAction.getClasspath().addMavenLibraries(distConfig.libraries());

        builder.action(compileAction);
        builder.build();
        return compiledOutput;
    }

    private NodeOutput addMergeWithSourcesStep(NodeOutput compiledOutput, NodeOutput sourcesOutput) {
        var builder = graph.nodeBuilder("mergeWithSources");
        builder.input("classes", compiledOutput.asInput());
        builder.input("sources", sourcesOutput.asInput());
        var output = builder.output("output", NodeOutputType.JAR, "Compiled minecraft sources including sources");
        builder.action(new MergeWithSourcesAction());
        builder.build();

        return output;
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
                    if (variable.equals("listLibrariesOutput")) {
                        // We fold listLibraries inside the steps that use it
                        continue;
                    }
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

                // When generating Minecraft artifacts that join the client and server, we generate a MANIFEST.MF that
                // indicates files exclusive to one or the other. This started in Minecraft 1.21.6.
                if (processGeneration.generateDistSourceManifest() && config.dist().equals("joined")) {
                    if ("stripClient".equals(step.getId())) {
                        // Prefer the already extracted server, otherwise download it
                        var serverJarInput = graph.hasOutput("extractServer", "output") ?
                                graph.getRequiredOutput("extractServer", "output").asInput()
                                : graph.getRequiredOutput("downloadServer", "output").asInput();

                        action.generateSplitManifest("client", "server");
                        builder.input(SplitResourcesFromClassesAction.INPUT_OTHER_DIST_JAR, serverJarInput);
                        if (graph.hasOutput("mergeMappings", "output")) {
                            builder.input(SplitResourcesFromClassesAction.INPUT_MAPPINGS, graph.getRequiredOutput("mergeMappings", "output").asInput());
                        }
                    } else if ("stripServer".equals(step.getId())) {
                        action.generateSplitManifest("server", "client");
                        builder.input(SplitResourcesFromClassesAction.INPUT_OTHER_DIST_JAR, graph.getRequiredOutput("downloadClient", "output").asInput());
                        if (graph.hasOutput("mergeMappings", "output")) {
                            builder.input(SplitResourcesFromClassesAction.INPUT_MAPPINGS, graph.getRequiredOutput("mergeMappings", "output").asInput());
                        }
                    }
                }

                processGeneration.getAdditionalDenyListForMinecraftJars().forEach(action::addDenyPatterns);
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
                PatchActionFactory.makeAction(
                        builder,
                        getRequiredDataSource("patches"),
                        null,
                        "a/",
                        "b/"
                );
            }
            default -> {
                var function = config.getFunction(step.type());
                if (function == null) {
                    throw new IllegalArgumentException("Step " + step.getId() + " references undefined function " + step.type());
                }

                applyFunctionToNode(config, step, function, builder);
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

    private void applyFunctionToNode(NeoFormDistConfig config, NeoFormStep step, NeoFormFunction function, ExecutionNodeBuilder builder) {
        var type = switch (step.type()) {
            case "mergeMappings" -> NodeOutputType.TSRG;
            case "generateSplitManifest" -> NodeOutputType.JAR_MANIFEST;
            default -> NodeOutputType.JAR;
        };

        applyFunctionToNode(config.libraries(), step.values(), type, function, builder);
    }

    @Nullable
    public NodeOutput applyFunctionToNode(List<MavenCoordinate> libraries,
                                          Map<String, String> placeholders,
                                          NodeOutputType outputType,
                                          NeoFormFunction function,
                                          ExecutionNodeBuilder builder) {
        var resolvedJvmArgs = new ArrayList<>(Objects.requireNonNullElse(function.jvmargs(), List.of()));
        var resolvedArgs = new ArrayList<>(Objects.requireNonNullElse(function.args(), List.of()));

        // At runtime, {placeholder} in the function arguments can refer to node inputs, node outputs or data (see ProcessingEnvironment#interpolateString).
        // Variables assigned to outputs of other nodes have already been added as node inputs and can be used directly.
        // Any constants or references to data need to be eagerly resolved since those are not supported as node inputs.
        for (var entry : placeholders.entrySet()) {
            if (builder.hasInput(entry.getKey())) {
                continue; // This placeholder was already declared as a node input (and referes to the output of another node)
            }
            UnaryOperator<String> resolver = s -> s.replace("{" + entry.getKey() + "}", entry.getValue());
            resolvedJvmArgs.replaceAll(resolver);
            resolvedArgs.replaceAll(resolver);
        }

        NodeOutput[] mainOutput = new NodeOutput[1];

        // Now resolve the remaining placeholders.
        Set<String> dataSourcesUsed = new HashSet<>();
        boolean[] usesListLibraries = new boolean[]{false};
        Consumer<String> placeholderProcessor = text -> {
            var matcher = NeoFormInterpolator.TOKEN_PATTERN.matcher(text);
            while (matcher.find()) {
                var variable = matcher.group(1);

                // Handle the "magic" output variable. In NeoForm JSON, it's impossible to know which
                // variables are truly intended to be outputs.
                if ("output".equals(variable)) {
                    if (!builder.hasOutput(variable)) {
                        mainOutput[0] = builder.output(variable, outputType, "Output of step " + outputType);
                    }
                } else if (dataSources.containsKey(variable)) {
                    // It likely refers to data from the NeoForm zip, this will be handled by the runtime later
                    // But we do need to add a cache-key dependency on the NeoForm data file
                    dataSourcesUsed.add(variable);
                } else if ("listLibrariesOutput".equals(variable)) {
                    usesListLibraries[0] = true;
                } else if (variable.endsWith("Output")) {
                    // The only remaining supported variable form is referencing outputs of other steps
                    // this is done via <stepName>Output.
                    var otherStep = variable.substring(0, variable.length() - "Output".length());
                    builder.inputFromNodeOutput(variable, otherStep, "output");
                } else if (variable.equals("log")) {
                    // This variable is used in legacy MCP config JSONs to signify the path to a logfile and is ignored here
                } else if (builder.hasInput(variable)) {
                    // The variable was already set by the step and added as an input to the node, so we can safely use it
                } else {
                    throw new IllegalArgumentException("Unsupported variable " + variable + " used by step " + builder.id());
                }
            }
        };
        resolvedJvmArgs.forEach(placeholderProcessor);
        resolvedArgs.forEach(placeholderProcessor);

        var action = new ExternalJavaToolAction(getFunctionClasspath(builder.id(), function), function.mainClass());

        action.setRepositoryUrl(function.repository());
        action.setJvmArgs(resolvedJvmArgs);
        action.setArgs(resolvedArgs);
        // Add every referenced data source to the cache key
        for (var dataSourceId : dataSourcesUsed) {
            var dataSource = Objects.requireNonNull(dataSources.get(dataSourceId), dataSourceId);
            action.addDataDependencyHash(dataSourceId, dataSource::cacheKey);
        }
        builder.action(action);

        if (usesListLibraries[0]) {
            builder.inputFromNodeOutput("versionManifest", "downloadJson", "output");
            var listLibraries = new CreateLibrariesOptionsFile();
            listLibraries.getClasspath().setOverriddenClasspath(buildOptions.getOverriddenCompileClasspath());
            listLibraries.getClasspath().addMavenLibraries(libraries);
            action.setListLibraries(listLibraries);
        }

        return mainOutput[0];
    }

    private static List<MavenCoordinate> getFunctionClasspath(String step, NeoFormFunction function) {
        List<String> toolClasspath = new ArrayList<>();
        if (function.toolArtifact() != null) {
            if (function.classpath() != null || function.mainClass() != null) {
                throw new IllegalArgumentException("Function for step " + step + " combines legacy 'version' attribute with 'classpath' or 'main_class'");
            }
            toolClasspath.add(function.toolArtifact());
        } else if (function.classpath() != null) {
            toolClasspath.addAll(function.classpath());
            if (function.mainClass() == null && function.classpath().size() != 1) {
                throw new IllegalArgumentException("Function for step " + step + " must define the main_class because it declares a classpath with not exactly one item.");
            }
        } else {
            throw new IllegalArgumentException("Function for step " + step + " is missing both version and classpath.");
        }
        List<MavenCoordinate> toolClasspathCoordinates = new ArrayList<>(toolClasspath.size());
        for (String artifactId : toolClasspath) {
            try {
                toolClasspathCoordinates.add(MavenCoordinate.parse(artifactId));
            } catch (Exception e) {
                throw new IllegalArgumentException("Function for step " + step + " has invalid tool: " + artifactId);
            }
        }
        return toolClasspathCoordinates;
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
            LOG.println(AnsiColor.MUTED + StringUtil.indent(cacheKey.describe(), 2) + AnsiColor.RESET);
        }

        try (var lock = lockManager.lock(cacheKey.toString())) {
            var outputValues = new HashMap<String, Path>();

            if (cacheManager.restoreOutputsFromCache(node, cacheKey, outputValues)) {
                node.complete(outputValues, true);
                return;
            }

            var workspace = cacheManager.createWorkspace(node.id());
            node.action().run(new NodeProcessingEnvironment(workspace, node, outputValues));

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

    public Map<String, Path> createResults(String... ids) throws InterruptedException {
        // Determine the nodes we need to run
        Set<ExecutionNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : ids) {
            var nodeOutput = graph.getResult(id);
            if (verbose) {
                LOG.println(AnsiColor.MUTED + "Requested output " + id + " was mapped to " + nodeOutput + AnsiColor.RESET);
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

    public void applyTransform(GraphTransform transform) {
        transform.apply(this, graph);
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

    public ProcessGeneration getProcessGeneration() {
        return processGeneration;
    }

    public void setJavaHome(Path javaHome) {

        Path javaExecutable;
        if (OsUtil.isWindows()) {
            javaExecutable = javaHome.resolve("bin/java.exe");
        } else {
            javaExecutable = javaHome.resolve("bin/java");
        }

        if (!Files.isExecutable(javaExecutable)) {
            throw new RuntimeException("Could not find a Java executable in the given Java home: " + javaExecutable);
        }

        setJavaExecutable(javaExecutable.toString());
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable;
        this.javaExecutableInformation = JavaInstallationInformation.fromExecutable(javaExecutable);
    }

    public ProblemReporter getProblemReporter() {
        return problemReporter;
    }

    public void setProblemReporter(ProblemReporter problemReporter) {
        this.problemReporter = Objects.requireNonNull(problemReporter, "problemReporter");
    }

    public FileHashService getFileHashingService() {
        return fileHashService;
    }

    private class NodeProcessingEnvironment implements ProcessingEnvironment {
        private final Path workspace;
        private final ExecutionNode node;
        private final Map<String, Path> outputValues;

        public NodeProcessingEnvironment(Path workspace, ExecutionNode node, Map<String, Path> outputValues) {
            this.workspace = workspace;
            this.node = node;
            this.outputValues = outputValues;
        }

        @Override
        public ArtifactManager getArtifactManager() {
            return artifactManager;
        }

        @Override
        public Path getWorkspace() {
            return workspace;
        }

        @Override
        public String getJavaExecutable() {
            return javaExecutable;
        }

        @Override
        public @Nullable JavaInstallationInformation getJavaExecutableInformation() {
            return javaExecutableInformation;
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

            return getPathArgument(resultPath);
        }

        public Path extractData(String dataId) {
            var dataSource = dataSources.get(dataId);
            if (dataSource == null) {
                throw new IllegalArgumentException("Could not find data source " + dataId
                        + ". Available: " + dataSources.keySet());
            }

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

        @Override
        public <T> T getRequiredInput(String id, ResultRepresentation<T> representation) throws IOException {
            return node.getRequiredInput(id).getValue(representation);
        }

        @Nullable
        @Override
        public <T> T getInput(String id, ResultRepresentation<T> representation) throws IOException {
            return node.hasInput(id) ? node.getRequiredInput(id).getValue(representation) : null;
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

        @Override
        public ProblemReporter getProblemReporter() {
            return problemReporter;
        }
    }

    public void setMcpMappingsData(Path mcpMappingsData) {
        this.mcpMappingsData = mcpMappingsData;
    }
}
