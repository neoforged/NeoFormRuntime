package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches for installation locations of the Minecraft launcher.
 */
public class LauncherInstallations {
    private static final Logger LOG = Logger.create();

    // Some are sourced from:
    // https://github.com/SpongePowered/VanillaGradle/blob/ccc45765d9881747b2c922be7a13c453c32ce9ed/subprojects/gradle-plugin/src/main/java/org/spongepowered/gradle/vanilla/internal/Constants.java#L61-L71
    // Placeholders of the form ${variable} are interpreted as system properties.
    // If the variable starts with "env.", it is sourced from environment variables.
    private static final String[] CANDIDATES = {
            "${env.APPDATA}/.minecraft/", // Windows, default launcher
            "${user.home}/.minecraft/", // linux, default launcher
            "${user.home}/Library/Application Support/minecraft/", // macOS, default launcher
            "${user.home}/curseforge/minecraft/Install/", // Windows, Curseforge Client
            "${env.APPDATA}/com.modrinth.theseus/meta/", // Windows, Modrinth App
            "${env.LOCALAPPDATA}/.ftba/bin/", // Windows, FTB App
            "${user.home}/.local/share/PrismLauncher/", // linux, PrismLauncher
            "${user.home}/.local/share/multimc/", // linux, MultiMC
            "${user.home}/Library/Application Support/PrismLauncher/", // macOS, PrismLauncher
            "${env.APPDATA}/PrismLauncher/", // Windows, PrismLauncher
            "${user.home}/scoop/persist/multimc/", // Windows, MultiMC via Scoop
    };
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    // Sort launcher directories in descending order by the number of asset indices they contain
    private static final Comparator<LauncherDirectory> ASSET_INDEX_COUNT_DESCENDING = Comparator.<LauncherDirectory>comparingInt(d -> d.assetIndices.size()).reversed();

    private final List<LauncherDirectory> launcherDirectories = new ArrayList<>();

    /** If true, the scan was already performed and launcherDirectories is up to date */
    private boolean scanned;

    private boolean verbose;

    public LauncherInstallations(List<Path> additionalLauncherDirs) throws IOException {
        for (var dir : additionalLauncherDirs) {
            var launcherDir = analyzeLauncherDirectory(dir);
            if (launcherDir == null) {
                throw new NoSuchFileException(dir.toString());
            }
            launcherDirectories.add(launcherDir);
        }
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Returns all installation roots that have been found.
     * Please note that none of the directories or files you might
     * expect might actually exist or be readable.
     */
    public List<Path> getInstallationRoots() {
        scanIfNecessary();

        return launcherDirectories.stream().map(LauncherDirectory::directory).toList();
    }

    @Nullable
    public Path getAssetDirectoryForIndex(String assetIndexId) {
        scanIfNecessary();

        var haveIndex = new ArrayList<LauncherDirectory>();
        for (var launcherDirectory : launcherDirectories) {
            if (launcherDirectory.assetIndices.contains(assetIndexId)) {
                haveIndex.add(launcherDirectory);
            }
        }

        // Sort by count of other indices descending
        haveIndex.sort(ASSET_INDEX_COUNT_DESCENDING);

        if (!haveIndex.isEmpty()) {
            return haveIndex.getFirst().assetDirectory();
        }

        return null;
    }

    public List<Path> getAssetRoots() {
        scanIfNecessary();

        var launcherDirsWithAssets = new ArrayList<LauncherDirectory>();
        for (var launcherDirectory : launcherDirectories) {
            if (launcherDirectory.assetDirectory() != null) {
                launcherDirsWithAssets.add(launcherDirectory);
            }
        }

        launcherDirsWithAssets.sort(ASSET_INDEX_COUNT_DESCENDING);

        return launcherDirsWithAssets.stream().map(LauncherDirectory::assetDirectory).toList();
    }

    private void scanIfNecessary() {
        if (scanned) {
            return;
        }
        scanned = true;

        // In CI, we can assume that the Minecraft launcher is not going to be installed.
        // Skip scanning for it there.
        // See: https://docs.gitlab.com/ee/ci/variables/predefined_variables.html
        // See: https://docs.github.com/en/actions/learn-github-actions/variables#default-environment-variables
        if ("true".equals(System.getenv("CI"))) {
            if (verbose) {
                LOG.println("Not scanning for Minecraft Launcher installations in CI");
            }
            return;
        }

        if (verbose) {
            LOG.println("Scanning for Minecraft Launcher installations");
        }

        for (var candidate : CANDIDATES) {
            var resolvedPath = resolvePlaceholders(candidate);
            if (resolvedPath == null) {
                continue;
            }

            try {
                var result = analyzeLauncherDirectory(Paths.get(candidate));
                if (result != null) {
                    launcherDirectories.add(result);
                }
            } catch (Exception e) {
                if (verbose) {
                    LOG.println(" Failed to scan launcher directory " + candidate + ": " + e);
                }
            }
        }

        if (verbose) {
            LOG.println("Launcher directories found:");
            for (var launcherDirectory : launcherDirectories) {
                String details;
                if (launcherDirectory.assetDirectory == null || launcherDirectory.assetIndices().isEmpty()) {
                    details = "no assets";
                } else {
                    details = "asset indices: " + String.join(" ", launcherDirectory.assetIndices());
                }

                LOG.println(AnsiColor.MUTED + "  " + launcherDirectory.directory
                            + " (" + details + ")" + AnsiColor.RESET);
            }
        }
    }

    @Nullable
    private String resolvePlaceholders(String candidate) {
        var matcher = PLACEHOLDER_PATTERN.matcher(candidate);

        var unmatchedVariables = new ArrayList<String>();
        candidate = matcher.replaceAll(match -> {
            var variable = match.group(1);
            String value;
            if (variable.startsWith("env.")) {
                value = System.getenv(variable.substring("env.".length()));
            } else {
                value = System.getProperty(variable);
            }

            if (value == null) {
                unmatchedVariables.add(variable);
                return "";
            }
            return Matcher.quoteReplacement(value);
        });
        if (!unmatchedVariables.isEmpty()) {
            if (verbose) {
                LOG.println("  Skipping candidate " + candidate + " due to undefined references: " + unmatchedVariables);
            }
            return null; // Ignoring due to unmatched variables
        }
        return candidate;
    }

    @Nullable
    private LauncherDirectory analyzeLauncherDirectory(Path installDir) throws IOException {
        if (!Files.isDirectory(installDir)) {
            if (verbose) {
                LOG.println(AnsiColor.MUTED + " Not found: " + installDir + AnsiColor.RESET);
            }
            return null;
        }

        var assetIndices = new HashSet<String>();
        var assetRoot = installDir.resolve("assets");
        var assetIndicesDir = assetRoot.resolve("indexes");
        var assetObjectsDir = assetRoot.resolve("objects");
        if (Files.isDirectory(assetIndicesDir) && Files.isDirectory(assetObjectsDir)) {
            // Count the number of asset indices present to judge how viable this directory is
            try (var stream = Files.list(assetIndicesDir)) {
                stream.map(f -> f.getFileName().toString())
                        .filter(f -> f.endsWith(".json"))
                        .map(f -> f.substring(0, f.length() - 5))
                        .forEach(assetIndices::add);
            }
        }
        if (assetIndices.isEmpty()) {
            assetRoot = null; // Do not use an asset root, when it is empty
        }

        return new LauncherDirectory(installDir, assetRoot, assetIndices);
    }

    private record LauncherDirectory(Path directory, @Nullable Path assetDirectory, Set<String> assetIndices) {
    }
}
