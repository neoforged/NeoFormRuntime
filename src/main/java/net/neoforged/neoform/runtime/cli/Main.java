package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.utils.OsUtil;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(name = "neoform-runtime", subcommands = {RunNeoFormCommand.class, DownloadAssetsCommand.class, CleanCacheCommand.class, CacheMaintenance.class}, mixinStandardHelpOptions = true)
public class Main {
    @Option(names = "--home-dir", scope = CommandLine.ScopeType.INHERIT, description = "Where NFRT should store caches.")
    Path homeDir = getDefaultHomeDir();

    @Option(names = "--work-dir", scope = CommandLine.ScopeType.INHERIT, description = "Where temporary working directories are stored. Defaults to the subfolder 'work' in the NFRT home dir.")
    Path workDir;

    @Option(names = "--repository", arity = "*", scope = CommandLine.ScopeType.INHERIT, description = "Add a Maven repository for downloading artifacts.")
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"), Path.of(System.getProperty("user.home"), ".m2", "repository").toUri());

    @Option(names = "--artifact-manifest", scope = CommandLine.ScopeType.INHERIT)
    Path artifactManifest;

    @CommandLine.Option(names = "--launcher-meta-uri", scope = CommandLine.ScopeType.INHERIT)
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    @CommandLine.Option(names = "--verbose", description = "Enable verbose output", scope = CommandLine.ScopeType.INHERIT)
    boolean verbose;

    public Path getWorkDir() {
        return Objects.requireNonNullElse(workDir, homeDir);
    }

    private static Path getDefaultHomeDir() {
        var userHomeDir = Paths.get(System.getProperty("user.home"));

        if (OsUtil.isLinux()) {
            var xdgCacheHome = System.getenv("XDG_CACHE_DIR");
            if (xdgCacheHome != null && xdgCacheHome.startsWith("/")) {
                return Paths.get(xdgCacheHome).resolve("neoform");
            } else {
                return userHomeDir.resolve(".cache/neoform");
            }
        }
        return userHomeDir.resolve(".neoform");

    }

    public static void main(String... args) {
        var commandLine = new CommandLine(new Main());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
