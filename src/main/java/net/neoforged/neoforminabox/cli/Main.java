package net.neoforged.neoforminabox.cli;

import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(subcommands = {RunNeoFormCommand.class, DownloadAssetsCommand.class}, mixinStandardHelpOptions = true)
public class Main {
    @Option(names = "--home-dir")
    Path cacheDir = Paths.get(System.getProperty("user.home")).resolve(".neoform");

    @Option(names = "--repository", arity = "*")
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"));

    @Option(names = "--artifact-manifest")
    Path artifactManifest;

    @CommandLine.Option(names = "--launcher-meta-uri")
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    public static void main(String... args) {
        var commandLine = new CommandLine(new Main());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
