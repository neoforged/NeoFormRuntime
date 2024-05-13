package net.neoforged.neoform.runtime.cli;

import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(name = "neoform-runtime", subcommands = {RunNeoFormCommand.class, DownloadAssetsCommand.class}, mixinStandardHelpOptions = true)
public class Main {
    @Option(names = "--home-dir", scope = CommandLine.ScopeType.INHERIT)
    Path cacheDir = Paths.get(System.getProperty("user.home")).resolve(".neoform");

    @Option(names = "--repository", arity = "*", scope = CommandLine.ScopeType.INHERIT)
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"), Path.of(System.getProperty("user.home"), ".m2", "repository").toUri());

    @Option(names = "--artifact-manifest", scope = CommandLine.ScopeType.INHERIT)
    Path artifactManifest;

    @CommandLine.Option(names = "--launcher-meta-uri", scope = CommandLine.ScopeType.INHERIT)
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    public static void main(String... args) {
        var commandLine = new CommandLine(new Main());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
