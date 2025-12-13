package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.downloads.AssetDownloadResult;
import net.neoforged.neoform.runtime.downloads.AssetDownloader;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadsFailedException;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * @see AssetDownloader
 */
@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand extends MinecraftCommand {
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

    @Override
    protected int runMinecraftCommand(DownloadManager downloadManager,
                                      CacheManager cacheManager,
                                      LockManager lockManager,
                                      ArtifactManager artifactManager,
                                      LauncherInstallations launcherInstallations,
                                      String minecraftVersion) throws IOException {
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
