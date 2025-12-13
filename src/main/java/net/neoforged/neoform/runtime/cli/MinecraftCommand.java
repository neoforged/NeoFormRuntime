package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import picocli.CommandLine;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * Base class for commands that work directly with a Minecraft version.
 */
public abstract class MinecraftCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(multiplicity = "1")
    public DownloadArtifactsCommand.Version version;

    public static class Version {
        @CommandLine.Option(names = "--minecraft-version")
        String minecraftVersion;
        @CommandLine.Option(names = "--neoform")
        String neoformArtifact;
        @CommandLine.Option(names = "--neoforge")
        String neoforgeArtifact;
    }

    @Override
    public Integer call() throws Exception {
        try (var downloadManager = new DownloadManager();
             var cacheManager = commonOptions.createCacheManager()) {
            var lockManager = commonOptions.createLockManager();

            var launcherInstallations = commonOptions.createLauncherInstallations();
            var artifactManager = commonOptions.createArtifactManager(cacheManager, downloadManager, lockManager, launcherInstallations);

            var minecraftVersion = getMinecraftVersion(artifactManager);

            return runMinecraftCommand(
                    downloadManager,
                    cacheManager,
                    lockManager,
                    artifactManager,
                    launcherInstallations,
                    minecraftVersion
            );
        }
    }

    protected abstract int runMinecraftCommand(DownloadManager downloadManager,
                                               CacheManager cacheManager, LockManager lockManager, ArtifactManager artifactManager,
                                               LauncherInstallations launcherInstallations, String minecraftVersion) throws IOException;

    private String getMinecraftVersion(ArtifactManager artifactManager) throws IOException {
        if (version.minecraftVersion != null) {
            return version.minecraftVersion;
        }

        Artifact neoFormArchive;
        if (version.neoformArtifact != null) {
            neoFormArchive = artifactManager.get(version.neoformArtifact);
        } else {
            // Pull from neoforge artifact then
            var neoforgeArtifact = artifactManager.get(version.neoforgeArtifact);
            try (var neoforgeZipFile = new JarFile(neoforgeArtifact.path().toFile())) {
                var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);
                neoFormArchive = artifactManager.get(neoforgeConfig.neoformArtifact());
            }
        }

        try (var zipFile = new ZipFile(neoFormArchive.path().toFile())) {
            return NeoFormConfig.from(zipFile).minecraftVersion();
        }
    }
}
