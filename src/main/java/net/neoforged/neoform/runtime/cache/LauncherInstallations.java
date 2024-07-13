package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.Logger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

    private final List<LauncherDirectory> launcherDirectories = new ArrayList<>();

    /** If true, the scan was already performed and launcherDirectories is up-to-date */
    private boolean scanned;

    private boolean verbose;

    public LauncherInstallations(List<Path> additionalLauncherDirs) {
        for (var dir : additionalLauncherDirs) {
            var launcherDir = analyzeLauncherDirectory(dir);
            if (launcherDir != null) {
                launcherDirectories.add(launcherDir);
            }
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
                LOG.println(AnsiColor.MUTED + "  " + launcherDirectory.directory
                            + AnsiColor.RESET);
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
    private LauncherDirectory analyzeLauncherDirectory(Path installDir) {
        if (!Files.isDirectory(installDir)) {
            if (verbose) {
                LOG.println(AnsiColor.MUTED + " Not found: " + installDir + AnsiColor.RESET);
            }
            return null;
        }

        return new LauncherDirectory(installDir);
    }

    private record LauncherDirectory(Path directory) {
    }
}
