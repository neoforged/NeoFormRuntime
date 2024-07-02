package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.OsUtil;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.IOException;
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

    @Option(names = "--assets-dir", scope = ScopeType.INHERIT, description = "Where NFRT should store Minecraft client assets. Defaults to a subdirectory of the home-dir.")
    @Nullable
    Path assetsDir;

    @Option(names = "--work-dir", scope = ScopeType.INHERIT, description = "Where temporary working directories are stored. Defaults to the subfolder 'work' in the NFRT home dir.")
    @Nullable
    Path workDir;

    @Option(names = "--repository", arity = "*", scope = ScopeType.INHERIT, description = "Overrides Maven repositories used for downloading artifacts.")
    List<URI> repositories = List.of(URI.create("https://maven.neoforged.net/releases/"), Path.of(System.getProperty("user.home"), ".m2", "repository").toUri());

    @Option(names = "--add-repository", arity = "*", scope = ScopeType.INHERIT, description = "Add Maven repositories for downloading artifacts.")
    List<URI> additionalRepositories = new ArrayList<>();

    @Option(names = "--launcher-dir", arity = "*", scope = ScopeType.INHERIT, description = "Specifies one or more Minecraft launcher installation directories. NFRT will try to reuse files from these directories.")
    List<Path> launcherDirs = new ArrayList<>();

    @Option(names = "--artifact-manifest", scope = ScopeType.INHERIT)
    @Nullable
    Path artifactManifest;

    @Option(
            names = "--warn-on-artifact-manifest-miss",
            scope = ScopeType.INHERIT,
            description = "Warns when an artifact manifest is given, but a file is being downloaded that is not in the manifest.",
            negatable = true,
            fallbackValue = "true"
    )
    boolean warnOnArtifactManifestMiss;

    @Option(names = "--launcher-meta-uri", scope = ScopeType.INHERIT)
    URI launcherManifestUrl = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    @Option(
            names = "--verbose",
            description = "Enable verbose output",
            scope = ScopeType.INHERIT,
            negatable = true,
            fallbackValue = "true"
    )
    boolean verbose;

    @Option(
            names = "--color",
            description = "Enable color console output",
            scope = ScopeType.INHERIT,
            negatable = true,
            fallbackValue = "true"
    )
    boolean color = shouldEnableColor();

    @Option(
            names = "--emojis",
            description = "Enable use of emojis in console output",
            scope = ScopeType.INHERIT,
            negatable = true,
            fallbackValue = "true"
    )
    boolean emojis = shouldEnableEmojis();

    /**
     * Windows console is sadly too finicky for now.
     */
    private boolean shouldEnableEmojis() {
        return OsUtil.isLinux() || OsUtil.isMac();
    }

    private static boolean shouldEnableColor() {
        return System.getenv("NO_COLOR") == null || System.getenv("NO_COLOR").isEmpty();
    }

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
        Logger.NO_COLOR = !baseCommand.color;
        Logger.NO_EMOJIS = !baseCommand.emojis;
        Logger.PRINT_THREAD = baseCommand.verbose;
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    public List<URI> getEffectiveRepositories() {
        var result = new ArrayList<>(repositories);
        result.addAll(additionalRepositories);
        return result;
    }

    public CacheManager createCacheManager() throws IOException {
        var cacheManager = new CacheManager(homeDir, assetsDir, getWorkDir());
        cacheManager.setVerbose(verbose);
        return cacheManager;
    }

    public LauncherInstallations createLauncherInstallations() {
        var installations = new LauncherInstallations(launcherDirs);
        installations.setVerbose(verbose);
        return installations;
    }

    public LockManager createLockManager() throws IOException {
        var lockManager = new LockManager(homeDir);
        lockManager.setVerbose(verbose);
        return lockManager;
    }

    public ArtifactManager createArtifactManager(CacheManager cacheManager,
                                                 DownloadManager downloadManager,
                                                 LockManager lockManager,
                                                 LauncherInstallations launcherInstallations) throws IOException {
        var artifactManager = new ArtifactManager(
                getEffectiveRepositories(),
                cacheManager,
                downloadManager,
                lockManager,
                launcherManifestUrl,
                launcherInstallations
        );
        artifactManager.setWarnOnArtifactManifestMiss(warnOnArtifactManifestMiss);

        if (artifactManifest != null) {
            artifactManager.loadArtifactManifest(artifactManifest);
        }

        return artifactManager;
    }
}
