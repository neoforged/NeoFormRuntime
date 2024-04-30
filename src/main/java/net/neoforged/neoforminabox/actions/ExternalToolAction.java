package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.cli.Artifact;
import net.neoforged.neoforminabox.cli.ArtifactManager;
import net.neoforged.neoforminabox.cli.NeoFormInterpolator;
import net.neoforged.neoforminabox.cli.ProcessingEnvironment;
import net.neoforged.neoforminabox.config.neoform.NeoFormDistConfig;
import net.neoforged.neoforminabox.config.neoform.NeoFormFunction;
import net.neoforged.neoforminabox.config.neoform.NeoFormStep;
import net.neoforged.neoforminabox.graph.ExecutionNodeAction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.ZipFile;

/**
 * Runs an external standalone tool
 */
public class ExternalToolAction implements ExecutionNodeAction {
    private final ArtifactManager artifactManager;
    private final NeoFormStep step;
    private final NeoFormFunction function;
    private final NeoFormDistConfig config;
    private final ZipFile archive;

    public ExternalToolAction(ArtifactManager artifactManager,
                              NeoFormStep step,
                              NeoFormFunction function,
                              NeoFormDistConfig config,
                              ZipFile archive) {
        this.artifactManager = artifactManager;
        this.step = step;
        this.function = function;
        this.config = config;
        this.archive = archive;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
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

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        ck.add("external tool", function.toolArtifact());
        if (function.repository() != null) {
            ck.add("external tool repository", function.repository().toString());
        }
        ck.add("command line arg", String.join(" ", function.args()));
        ck.add("jvm args", String.join(" ", function.jvmargs()));
    }
}
