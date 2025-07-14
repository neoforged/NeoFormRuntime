package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Runs an external standalone Java-based tool from a standalone executable jar-file.
 */
public class ExternalJavaToolAction implements ExecutionNodeAction {
    private static final Logger LOG = Logger.create();

    /**
     * The Maven coordinate of the tool
     */
    private MavenCoordinate toolArtifactId;
    /**
     * Specific maven repository URL to load the tool from.
     */
    @Nullable
    private URI repositoryUrl;
    private List<String> jvmArgs = new ArrayList<>();
    private List<String> args = new ArrayList<>();
    @Nullable
    private ListLibraries listLibraries = null;

    /**
     * Tools that are referenced by the NeoForm/MCP process files usually are only guaranteed to run
     * with the Java version that was current at the time.
     * NFRT offers a --java-executable option to set an appropriate (older) Java version.
     * Some tools, however, are referenced by NFRT itself and added to the graph, and those will usually
     * only run with the Java that NFRT itself is running with.
     * This option should be used for such tools.
     */
    private final boolean useHostJavaExecutable;

    public ExternalJavaToolAction(MavenCoordinate toolArtifactId) {
        this.toolArtifactId = toolArtifactId;
        // Tools referenced by maven coordinate come from the MCP/NeoForm config file and will usually only
        // be tested against the Java version used by that Minecraft version.
        this.useHostJavaExecutable = false;
    }

    public ExternalJavaToolAction(ToolCoordinate toolCoordinate) {
        this.toolArtifactId = toolCoordinate.version();
        // Tools referenced by tool coordinate are internal tools that are verified to run with the Java
        // version that NFRT itself can run with.
        this.useHostJavaExecutable = true;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var listLibrariesFile = listLibraries != null ? listLibraries.writeFile(environment) : null;

        Artifact toolArtifact;
        if (repositoryUrl != null) {
            toolArtifact = environment.getArtifactManager().get(toolArtifactId, repositoryUrl);
        } else {
            toolArtifact = environment.getArtifactManager().get(toolArtifactId);
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

        command.add("-jar");
        command.add(environment.getPathArgument(toolArtifact.path()));

        // Program Arguments
        for (var arg : args) {
            // For specific tasks we "fixup" the neoform spec
            if (toolArtifactId.groupId().equals("org.vineflower") && toolArtifactId.artifactId().equals("vineflower")) {
                arg = arg.replace("TRACE", "WARN");
            }
            if (listLibrariesFile != null) {
                arg = arg.replace("{listLibrariesOutput}", environment.getPathArgument(listLibrariesFile));
            }

            command.add(environment.interpolateString(arg));
        }

        LOG.println(" ↳ Running external tool " + toolArtifactId);
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

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        ck.add("external tool", toolArtifactId.toString());
        if (repositoryUrl != null) {
            ck.add("external tool repository", repositoryUrl.toString());
        }
        ck.add("command line arg", String.join(" ", args));
        ck.add("jvm args", String.join(" ", jvmArgs));
        if (listLibraries != null) {
            listLibraries.computeCacheKey(ck);
        }
    }

    public MavenCoordinate getToolArtifactId() {
        return toolArtifactId;
    }

    public void setToolArtifactId(MavenCoordinate toolArtifactId) {
        this.toolArtifactId = Objects.requireNonNull(toolArtifactId);
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
    public ListLibraries getListLibraries() {
        return this.listLibraries;
    }

    public void setListLibraries(@Nullable ListLibraries listLibraries) {
        this.listLibraries = listLibraries;
    }
}
