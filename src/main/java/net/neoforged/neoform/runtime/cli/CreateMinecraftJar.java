package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.actions.ExternalJavaToolAction;
import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "create-minecraft-jar", description = "Create a Minecraft jar using a binary patching-based system.")
public class CreateMinecraftJar implements Callable<Integer> {
    private static final Logger LOG = Logger.create();

    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    RunNeoFormCommand.SourceArtifacts sourceArtifacts;

    @CommandLine.Option(names = "--output", required = true)
    String output;

    // TODO: what about AT validation here?
    @CommandLine.Option(names = "--access-transformer", arity = "*", description = "path to an access transformer file, which widens the access modifiers of classes/methods/fields")
    List<String> additionalAccessTransformers = new ArrayList<>();

    @CommandLine.Option(names = "--interface-injection-data", arity = "*", description = "path to an interface injection data file, which extends classes with implements/extends clauses.")
    List<Path> interfaceInjectionDataFiles = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        // TODO: print time

        var launcherInstallations = commonOptions.createLauncherInstallations();

        try (var cacheManager = commonOptions.createCacheManager();
                var downloadManager = new DownloadManager()) {
            var lockManager = commonOptions.createLockManager();
            var artifactManager = commonOptions.createArtifactManager(cacheManager, downloadManager, lockManager, launcherInstallations);

            Artifact neoformArtifact;
            File binpatches;
            if (sourceArtifacts.neoforge != null) {
                var neoforgeArtifact = artifactManager.get(sourceArtifacts.neoforge);
                var neoforgeZipFile = new JarFile(neoforgeArtifact.path().toFile());
                var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);

                // Allow it to be overridden with local or remote data
                if (sourceArtifacts.neoform != null) {
                    LOG.println("Overriding NeoForm version " + neoforgeConfig.neoformArtifact() + " with CLI argument " + sourceArtifacts.neoform);
                    neoformArtifact = artifactManager.get(sourceArtifacts.neoform);
                } else {
                    neoformArtifact = artifactManager.get(neoforgeConfig.neoformArtifact());
                }

                binpatches = File.createTempFile("neoforge_binpatches", ".lzma");
                try (var binpatchesIn = neoforgeZipFile.getInputStream(neoforgeZipFile.getEntry(neoforgeConfig.binpatchesFile()))) {
                    LOG.println("Extracting NeoForge UserDev binpatches to " + binpatches.getAbsolutePath());
                    Files.copy(binpatchesIn, binpatches.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                neoformArtifact = artifactManager.get(sourceArtifacts.neoform);
                binpatches = null;
            }

            var zipFile = new ZipFile(neoformArtifact.path().toFile());
            var config = NeoFormConfig.from(zipFile);

            var versionManifestArtifact = artifactManager.getVersionManifest(config.minecraftVersion());
            var versionManifest = MinecraftVersionManifest.from(versionManifestArtifact.path());

            // TODO: do in parallel
            var clientArtifact = artifactManager.downloadFromManifest(versionManifest, "client");
            var clientMappingsArtifact = artifactManager.downloadFromManifest(versionManifest, "client_mappings");
            var serverArtifact = artifactManager.downloadFromManifest(versionManifest, "server");

            var args = new ArrayList<String>();
            args.add("--task");
            args.add("PROCESS_MINECRAFT_JAR");

            args.add("--input");
            args.add(clientArtifact.path().toAbsolutePath().toString());
            // TODO: so which one is it?
            args.add("--input");
            args.add(serverArtifact.path().toAbsolutePath().toString());
            args.add("--input-mappings");
            args.add(clientMappingsArtifact.path().toAbsolutePath().toString());

            args.add("--output");
            args.add(Path.of(output).toAbsolutePath().toString());

            // If an AT path is added twice, the validated variant takes precedence
            for (var accessTransformer : additionalAccessTransformers) {
                // TODO: are ATs validated?
                args.add("--access-transformer");
                args.add(Path.of(accessTransformer).toAbsolutePath().toString());
            }

            for (var interfaceInjectionFile : interfaceInjectionDataFiles) {
                args.add("--interface-injection-data");
                args.add(interfaceInjectionFile.toAbsolutePath().toString());
            }

            args.add("--neoform-data");
            args.add(neoformArtifact.path().toAbsolutePath().toString());

            if (binpatches != null) {
                args.add("--apply-patches");
                args.add(binpatches.getAbsolutePath());
            }

            // TODO: apparently there is a different arg for neoforge vs neoform? final params?

            String javaExecutablePath;
//            if (useHostJavaExecutable) {
                javaExecutablePath = ProcessHandle.current()
                        .info()
                        .command()
                        .orElseThrow();
//            } else {
//                javaExecutablePath = environment.getJavaExecutable();
//            }

            // TODO: which temp folder do we want to use
            var workingDir = commonOptions.getWorkDir();

            // TODO: potentially the code to invoke an external process needs to be moved to a helper method in ExternalJavaToolAction

            var command = new ArrayList<String>();
            command.add(javaExecutablePath);

            // JVM
//            for (var jvmArg : jvmArgs) {
//                command.add(environment.interpolateString(jvmArg));
//            }

            // TODO: how to choose the version?
            var toolArtifact = artifactManager.get("net.neoforged.installertools:installertools:3.0.18-moddev-support:fatjar");

            command.add("-jar");
            command.add(toolArtifact.path().toAbsolutePath().toString());

            // Program Arguments
            command.addAll(args);

//            LOG.println(" ↳ Running external tool " + toolArtifactId);
//            if (environment.isVerbose()) {
//                LOG.println(" " + AnsiColor.MUTED + printableCommand(command) + AnsiColor.RESET);
//            }

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
                ExternalJavaToolAction.tailLogFile(logFile);

                throw new RuntimeException("Failed to execute tool");
            }
        }

        return 0;
    }
}
