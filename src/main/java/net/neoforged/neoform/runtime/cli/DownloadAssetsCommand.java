package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.AssetDownloadResult;
import net.neoforged.neoform.runtime.downloads.AssetDownloader;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadsFailedException;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * @see AssetDownloader
 */
@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(multiplicity = "1")
    public Version version;

    // Support overriding the asset root via an environment property, which is aimed at CI/CD using separate
    // cross-version caches for this.
    @CommandLine.Option(names = "--asset-root", defaultValue = "${env:NFRT_ASSET_ROOT}")
    public Path assetRoot;

    @CommandLine.Option(names = "--asset-repository", defaultValue = "${env:NFRT_ASSET_REPOSITORY}")
    public URI assetRepository = URI.create("https://resources.download.minecraft.net/");

    @CommandLine.Option(
            names = "--copy-launcher-assets",
            description = "Try to find the Minecraft Launcher in common locations and copy its assets",
            negatable = true,
            fallbackValue = "true"
    )
    public boolean copyLauncherAssets = true;

    @CommandLine.Option(
            names = "--use-launcher-asset-root",
            description = "Try to find an existing Minecraft Launcher asset root, and use it to store the requested assets",
            negatable = true,
            fallbackValue = "true"
    )
    public boolean useLauncherAssetRoot = true;

    @CommandLine.Option(names = "--concurrent-downloads")
    public int concurrentDownloads = 25;

    /**
     * Properties file that will receive the metadata of the asset index.
     */
    @CommandLine.Option(names = "--write-properties")
    public Path outputPropertiesPath;

    /**
     * Write a JSON file as it is used by the Neoform Start.java file
     * See:
     * https://github.com/neoforged/NeoForm/blob/c2f5c5eda5eeca2e554c51872c28d0e68bc244bc/versions/release/1.21/inject/mcp/client/Start.java
     */
    @CommandLine.Option(names = "--write-json")
    public Path outputJsonPath;

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

            var downloader = new AssetDownloader(downloadManager, artifactManager, launcherInstallations, cacheManager, assetRoot);
            AssetDownloadResult result;
            try {
                result = downloader.downloadAssets(
                        minecraftVersion,
                        assetRepository,
                        useLauncherAssetRoot,
                        copyLauncherAssets,
                        concurrentDownloads
                );
            } catch (DownloadsFailedException e) {
                System.err.println(e.getErrors().size() + " files failed to download");
                System.err.println("First error:");
                e.getErrors().getFirst().printStackTrace();
                return 1;
            }

            if (outputPropertiesPath != null) {
                result.writeAsProperties(outputPropertiesPath);
            }

            if (outputJsonPath != null) {
                result.writeAsJson(outputJsonPath);
            }
            return 0;
        }
    }

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
