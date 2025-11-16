package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs an external standalone Java-based tool from a standalone executable jar-file.
 */
public class ExternalJavaToolAction implements ExecutionNodeAction {
    private static final Logger LOG = Logger.create();

    /**
     * The classpath of the tool.
     */
    private final List<MavenCoordinate> classpath;
    /**
     * The tools main class. Can be null if {@link #classpath} contains exactly one item and that item is executable.
     */
    @Nullable
    private final String mainClass;
    /**
     * Specific maven repository URL to load the tool from.
     */
    @Nullable
    private URI repositoryUrl;
    private List<String> jvmArgs = new ArrayList<>();
    private List<String> args = new ArrayList<>();
    @Nullable
    private CreateLibrariesOptionsFile listLibraries = null;
    /**
     * If the external tool relies on data being made available by the environment
     * via argument interpolation, that external data has to be considered in the cache key.
     * The values are modeled as suppliers because they only need to be queried/computed
     * if the action is about to be run. Some NFRT runs may entirely skip it, and thus skip the hash too.
     */
    private final SequencedMap<String, Supplier<CacheKey.AnnotatedValue>> dataDependencyHashes = new LinkedHashMap<>();

    /**
     * Tools that are referenced by the NeoForm/MCP process files usually are only guaranteed to run
     * with the Java version that was current at the time.
     * NFRT offers a --java-executable option to set an appropriate (older) Java version.
     * Some tools, however, are referenced by NFRT itself and added to the graph, and those will usually
     * only run with the Java that NFRT itself is running with.
     * This option should be used for such tools.
     */
    private final boolean useHostJavaExecutable;

    public ExternalJavaToolAction(List<MavenCoordinate> classpath, String mainClass) {
        this.classpath = List.copyOf(classpath);
        this.mainClass = Objects.requireNonNull(mainClass, "mainClass");
        // Tools referenced by maven coordinate come from the MCP/NeoForm config file and will usually only
        // be tested against the Java version used by that Minecraft version.
        this.useHostJavaExecutable = false;
    }

    public ExternalJavaToolAction(ToolCoordinate toolCoordinate) {
        this.classpath = List.of(toolCoordinate.version());
        this.mainClass = null;
        // Tools referenced by tool coordinate are internal tools that are verified to run with the Java
        // version that NFRT itself can run with.
        this.useHostJavaExecutable = true;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var listLibrariesFile = listLibraries != null ? listLibraries.writeFile(environment) : null;

        List<Artifact> toolArtifacts = new ArrayList<>();
        for (var artifactId : classpath) {
            if (repositoryUrl != null) {
                toolArtifacts.add(environment.getArtifactManager().get(artifactId, repositoryUrl));
            } else {
                toolArtifacts.add(environment.getArtifactManager().get(artifactId));
            }
        }

        String javaExecutablePath;
        if (useHostJavaExecutable) {
            javaExecutablePath = ProcessHandle.current()
                    .info()
                    .command()
                    .orElseThrow();
        } else {
            javaExecutablePath = environment.getJavaExecutable();
        }

        var workingDir = environment.getWorkspace();

        var command = new ArrayList<String>();
        command.add(javaExecutablePath);

        // JVM
        for (var jvmArg : jvmArgs) {
            command.add(environment.interpolateString(jvmArg));
        }

        if (toolArtifacts.size() == 1 && mainClass == null) {
            command.add("-jar");
            command.add(environment.getPathArgument(toolArtifacts.getFirst().path()));
        } else {
            command.add("-cp");
            command.add(toolArtifacts.stream().map(a -> environment.getPathArgument(a.path())).collect(Collectors.joining(File.pathSeparator)));
            command.add(mainClass);
        }

        // Program Arguments
        boolean isVineflower = isVineflower();
        if (isVineflower) {
            command.add("--thread-count");
            command.add("4");
        }
        for (var arg : args) {
            // For specific tasks we "fixup" the neoform spec
            if (isVineflower) {
                arg = arg.replace("TRACE", "WARN");
            }
            if (listLibrariesFile != null) {
                arg = arg.replace("{listLibrariesOutput}", environment.getPathArgument(listLibrariesFile));
            }

            command.add(environment.interpolateString(arg));
        }

        if (mainClass != null) {
            LOG.println(" ↳ Running external tool " + mainClass);
        } else {
            LOG.println(" ↳ Running external tool " + classpath.getFirst());
        }
        if (environment.isVerbose()) {
            LOG.println(" " + AnsiColor.MUTED + printableCommand(command) + AnsiColor.RESET);
        }

        var logFile = workingDir.resolve("console_output.txt").toFile();
        // Write the full console command to the log-file for easier analysis
        try (var writer = new BufferedWriter(new FileWriter(logFile, StandardCharsets.UTF_8))) {
            writer.append("-".repeat(80)).append("\n\n");
            writer.append("Command-Line:\n");
            for (String s : command) {
                writer.append(" - ").append(s).append("\n");
            }
            writer.append("-".repeat(80)).append("\n\n");
        }

        var process = new ProcessBuilder()
                .directory(workingDir.toFile())
                .command(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start();

        var exitCode = process.waitFor();
        if (exitCode != 0) {
            // Try tailing the last few lines of the log-file
            tailLogFile(logFile);

            throw new RuntimeException("Failed to execute tool");
        }

        // Vineflower will exit with code 0 even if it encountered some OOMs that caused some methods to fail to decompile.
        // This is of course problematic since patch application or recompilation will fail.
        // So we scan the log file for any hint of a java.lang.OutOfMemoryError
        if (isVineflower && fileContains(logFile, "java.lang.OutOfMemoryError")) {
            // Tail the last few lines to provide some extra context
            tailLogFile(logFile);

            throw new RuntimeException("Vineflower ran out of memory during decompilation. Try again.");
        }
    }

    private boolean isVineflower() {
        if (Objects.equals(mainClass, "")) {
            return true;
        } else if (mainClass == null && classpath.size() == 1) {
            var toolArtifactId = classpath.getFirst();
            return toolArtifactId.groupId().equals("org.vineflower") && toolArtifactId.artifactId().equals("vineflower");
        } else {
            return false;
        }
    }

    private static String printableCommand(List<String> command) {
        return command.stream().map(arg -> {
            if (arg.contains("\"")) {
                return "\"" + arg + "\"";
            } else {
                return arg;
            }
        }).collect(Collectors.joining(" "));
    }

    private void tailLogFile(File logFile) {
        System.err.println("Last lines of " + logFile + ":");
        System.err.println("------------------------------------------------------------");
        try (var raf = new RandomAccessFile(logFile, "r")) {
            raf.seek(Math.max(0, raf.length() - 1));
            int bytesRead = 0;
            int linesRead = 0;
            while (raf.getFilePointer() > 0 && raf.getFilePointer() < raf.length() && bytesRead < 2048 && linesRead < 30) {
                byte b = raf.readByte();
                bytesRead++;
                if (b == '\n') {
                    linesRead++;
                }
                raf.seek(Math.max(0, raf.getFilePointer() - 2));
            }

            var toRead = raf.length() - raf.getFilePointer();
            var data = new byte[(int) toRead];
            raf.readFully(data);
            System.err.println(new String(data, StandardCharsets.UTF_8));

        } catch (IOException e) {
            System.err.println("Failed to tail log-file " + logFile);
        }
        System.err.println("------------------------------------------------------------");
    }

    private boolean fileContains(File file, String s) throws IOException {
        try (var reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        if (mainClass == null && classpath.size() == 1) {
            var toolArtifactId = classpath.getFirst();
            ck.add("external tool", toolArtifactId.toString());
        } else {
            ck.add("external tool main class", mainClass);
            for (int i = 0; i < classpath.size(); i++) {
                var component = String.format(Locale.ROOT, "external tool classpath[%03d]", i);
                ck.add(component, classpath.get(i).toString());
            }
        }
        if (repositoryUrl != null) {
            ck.add("external tool repository", repositoryUrl.toString());
        }
        ck.add("command line arg", String.join(" ", args));
        ck.add("jvm args", String.join(" ", jvmArgs));
        for (var entry : dataDependencyHashes.entrySet()) {
            ck.add("data[" + entry.getKey() + "]", entry.getValue().get());
        }
        if (listLibraries != null) {
            listLibraries.computeCacheKey(ck);
        }
        if (isVineflower()) {
            // Force re-run in case a broken decomp was cached before the OOM check was added
            ck.add("oom check", "true");
        }
    }

    @Nullable
    public URI getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(@Nullable URI repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = Objects.requireNonNull(jvmArgs);
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = Objects.requireNonNull(args);
    }

    @Nullable
    public CreateLibrariesOptionsFile getListLibraries() {
        return this.listLibraries;
    }

    public void setListLibraries(@Nullable CreateLibrariesOptionsFile listLibraries) {
        this.listLibraries = listLibraries;
    }

    /**
     * If this external tools result depends on external data, adds the hash value of that
     * external data to the cache key of this action under the given id.
     */
    public void addDataDependencyHash(String id, Supplier<CacheKey.AnnotatedValue> hash) {
        if (dataDependencyHashes.put(id, hash) != null) {
            throw new IllegalArgumentException("Data dependency " + id + " was registered twice.");
        }
    }
}
