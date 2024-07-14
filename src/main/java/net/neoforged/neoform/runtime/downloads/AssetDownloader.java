package net.neoforged.neoform.runtime.downloads;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetIndexReference;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Downloads the client-side assets necessary to run Minecraft.
 * Since Minecraft versions reuse various assets, Mojang has organized the assets into a sort of repository,
 * where an asset index maps a relative path to an asset unique identified by its content hash. The same
 * asset can be stored only once on disk and reused many times across Minecraft versions.
 * The assets stored this way are called "objects", while the JSON files describing the mapping of
 * paths to objects are called "asset index".
 * <p>
 * On disk, an asset root is a directory that contains a subfolder containing asset index files ("indexes"),
 * and a subfolder containing the actual objects ("objects").
 * <p>
 * The objects subfolder is further subdivided into 256 subfolders, each representing the first two characters
 * of a file content hash. Each of these subfolders will contain the actual objects whose hash starts with the
 * same characters as the folder name.
 * Example: {@code objects/af/af96f55a90eaf11b327f1b5f8834a051027dc506}, which is one of the Minecraft icon files.
 */
public class AssetDownloader {
    private static final Logger LOG = Logger.create();

    private static final String INDEX_FOLDER = "indexes";

    private static final String OBJECT_FOLDER = "objects";

    private final DownloadManager downloadManager;
    private final ArtifactManager artifactManager;
    private final LauncherInstallations launcherInstallations;
    private final CacheManager cacheManager;

    public AssetDownloader(DownloadManager downloadManager,
                           ArtifactManager artifactManager,
                           LauncherInstallations launcherInstallations,
                           CacheManager cacheManager) {
        this.downloadManager = downloadManager;
        this.artifactManager = artifactManager;
        this.launcherInstallations = launcherInstallations;
        this.cacheManager = cacheManager;
    }

    public AssetDownloadResult downloadAssets(String minecraftVersion,
                                              URI assetRepository,
                                              boolean useLauncherAssetRoot,
                                              boolean copyLauncherAssets,
                                              int concurrentDownloads) throws IOException, DownloadsFailedException {

        var versionManifest = MinecraftVersionManifest.from(artifactManager.getVersionManifest(minecraftVersion).path());
        var assetIndexReference = versionManifest.assetIndex();
        LOG.println("Downloading asset index " + assetIndexReference.id());

        var assetRoot = selectAssetRoot(useLauncherAssetRoot, assetIndexReference);
        prepareAssetRoot(assetRoot);

        var assetIndex = acquireAssetIndex(assetRoot, assetIndexReference);

        var objectsFolder = assetRoot.resolve(OBJECT_FOLDER);
        var objectsToDownload = assetIndex.objects().values().stream()
                .distinct() // The same object can be referenced multiple times
                .filter(obj -> {
                    var f = objectsFolder.resolve(obj.getRelativePath()).toFile();
                    return f.length() != obj.size() || obj.size() == 0 && !f.exists();
                })
                .toList();

        try (var downloader = new ParallelDownloader(downloadManager, concurrentDownloads, objectsFolder, objectsToDownload.size())) {
            if (copyLauncherAssets) {
                var objectDirectories = launcherInstallations.getAssetRoots()
                        .stream()
                        .map(d -> d.resolve("objects"))
                        .toList();

                downloader.setLocalSources(objectDirectories);
            }

            for (var object : objectsToDownload) {
                var spec = new AssetDownloadSpec(assetRepository, object);
                var objectHash = object.hash();
                String objectPath = objectHash.substring(0, 2) + "/" + objectHash;
                downloader.submitDownload(spec, objectPath);
            }
        }

        return new AssetDownloadResult(assetRoot, assetIndexReference.id());
    }

    private AssetIndex acquireAssetIndex(Path assetRoot, AssetIndexReference assetIndexReference) throws IOException {
        var indexFolder = assetRoot.resolve(INDEX_FOLDER);
        var assetIndexPath = indexFolder.resolve(assetIndexReference.id() + ".json");
        downloadManager.download(assetIndexReference, assetIndexPath);
        return AssetIndex.from(assetIndexPath);
    }

    private Path selectAssetRoot(boolean useLauncherAssetRoot, AssetIndexReference assetIndexReference) {
        Path assetRoot = null;
        if (useLauncherAssetRoot) {
            // We already may have an asset root with specifically the index we're looking for,
            // and it might not be the launcher directory with otherwise the most indices
            assetRoot = launcherInstallations.getAssetDirectoryForIndex(assetIndexReference.id());
            if (assetRoot == null) {
                var assetRoots = launcherInstallations.getAssetRoots();
                if (!assetRoots.isEmpty()) {
                    assetRoot = assetRoots.getFirst();
                }
            }
        }
        if (assetRoot == null) {
            assetRoot = cacheManager.getAssetsDir();
        }

        LOG.println("Using Minecraft asset root: " + assetRoot);
        return assetRoot;
    }

    private static void prepareAssetRoot(Path assetRoot) throws IOException {

        var indexFolder = assetRoot.resolve(INDEX_FOLDER);
        Files.createDirectories(indexFolder);
        var objectsFolder = assetRoot.resolve(OBJECT_FOLDER);
        Files.createDirectories(objectsFolder);

        // Pre-create all folders
        for (var i = 0; i < 256; i++) {
            var objectSubFolder = objectsFolder.resolve(HexFormat.of().toHexDigits(i, 2));
            Files.createDirectories(objectSubFolder);
        }

    }

    private static class AssetDownloadSpec implements DownloadSpec {
        private final URI assetsBaseUrl;
        private final AssetObject object;

        public AssetDownloadSpec(URI assetsBaseUrl, AssetObject object) {
            this.assetsBaseUrl = assetsBaseUrl;
            this.object = object;
        }

        @Override
        public URI uri() {
            return URI.create(assetsBaseUrl.toString() + object.getRelativePath());
        }

        @Override
        public int size() {
            return object.size();
        }

        @Override
        public @Nullable String checksum() {
            return object.hash();
        }

        @Override
        public @Nullable String checksumAlgorithm() {
            return "SHA1";
        }
    }
}
