package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.OsUtil;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;
import static picocli.CommandLine.ScopeType;

@Command(name = "neoform-runtime", subcommands = {RunNeoFormCommand.class, DownloadAssetsCommand.class, CleanCacheCommand.class, CacheMaintenance.class}, mixinStandardHelpOptions = true)
public class Main {
    @Option(names = "--home-dir", scope = ScopeType.INHERIT, description = "Where NFRT should store caches.")
    Path homeDir = getDefaultHomeDir();

    @Option(names = "--work-dir", scope = ScopeType.INHERIT, description = "Where temporary working directories are stored. Defaults to the subfolder 'work' in the NFRT home dir.")
    Path workDir;

    @Option(names = "--repository", arity = "*", scope = ScopeType.INHERIT, description = "Overriddes Maven repositories used for downloading artifacts.")
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"), Path.of(System.getProperty("user.home"), ".m2", "repository").toUri());

    @Option(names = "--add-repository", arity = "*", scope = ScopeType.INHERIT, description = "Add Maven repositories for downloading artifacts.")
    List<URI> additionalRepositories = new ArrayList<>();

    @Option(names = "--artifact-manifest", scope = ScopeType.INHERIT)
    Path artifactManifest;

    @Option(names = "--warn-on-artifact-manifest-miss", scope = ScopeType.INHERIT, description = "Warns when an artifact manifest is given, but a file is being downloaded that is not in the manifest.")
    boolean warnOnArtifactManifestMiss;

    @Option(names = "--launcher-meta-uri", scope = ScopeType.INHERIT)
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    @Option(names = "--verbose", description = "Enable verbose output", scope = ScopeType.INHERIT)
    boolean verbose;

    @Option(names = "--no-color", description = "Disable color console output", scope = ScopeType.INHERIT)
    boolean noColor = System.getenv("NO_COLOR") != null && !System.getenv("NO_COLOR").isEmpty();

    @Option(names = "--no-emojis", description = "Disable use of emojis in console output", scope = ScopeType.INHERIT)
    boolean noEmojis = false;

    public Path getWorkDir() {
        return Objects.requireNonNullElseGet(workDir, () -> homeDir.resolve("work"));
    }

    private static Path getDefaultHomeDir() {
        var userHomeDir = Paths.get(System.getProperty("user.home"));

        if (OsUtil.isLinux()) {
            var xdgCacheHome = System.getenv("XDG_CACHE_DIR");
            if (xdgCacheHome != null && xdgCacheHome.startsWith("/")) {
                return Paths.get(xdgCacheHome).resolve("neoformruntime");
            } else {
                return userHomeDir.resolve(".cache/neoformruntime");
            }
        }
        return userHomeDir.resolve(".neoformruntime");

    }

    public static void main(String... args) {
        var baseCommand = new Main();
        var commandLine = new CommandLine(baseCommand);
        commandLine.parseArgs(args);
        Logger.NO_COLOR = baseCommand.noColor;
        Logger.NO_EMOJIS = baseCommand.noEmojis;
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    public List<URI> getEffectiveRepositories() {
        var result = new ArrayList<>(repositories);
        result.addAll(additionalRepositories);
        return result;
    }
}
