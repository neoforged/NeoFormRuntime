package net.neoforged.neoform.runtime.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.downloads.DownloadsFailedException;
import net.neoforged.neoform.runtime.downloads.ParallelDownloader;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

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
@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand implements Callable<Integer> {
    private static final Logger LOG = Logger.create();

    private static final String INDEX_FOLDER = "indexes";

    private static final String OBJECT_FOLDER = "objects";

    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(multiplicity = "1")
    public Version version;

    @CommandLine.Option(names = "--asset-repository")
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
             var cacheManager = commonOptions.createCacheManager();
             var lockManager = commonOptions.createLockManager()) {

            var launcherInstallations = commonOptions.createLauncherInstallations();
            var artifactManager = commonOptions.createArtifactManager(cacheManager, downloadManager, lockManager, launcherInstallations);

            return downloadAssets(downloadManager, artifactManager, launcherInstallations, cacheManager);
        }
    }

    private int downloadAssets(DownloadManager downloadManager,
                               ArtifactManager artifactManager,
                               LauncherInstallations launcherInstallations,
                               CacheManager cacheManager) throws IOException {

        var minecraftVersion = getMinecraftVersion(artifactManager);

        var versionManifest = MinecraftVersionManifest.from(artifactManager.getVersionManifest(minecraftVersion).path());
        var assetIndexReference = versionManifest.assetIndex();
        LOG.println("Downloading asset index " + assetIndexReference.id());

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
        prepareAssetRoot(assetRoot);

        var indexFolder = assetRoot.resolve(INDEX_FOLDER);
        var objectsFolder = assetRoot.resolve(OBJECT_FOLDER);

        var assetIndexPath = indexFolder.resolve(assetIndexReference.id() + ".json");
        downloadManager.download(assetIndexReference, assetIndexPath);

        var assetIndex = AssetIndex.from(assetIndexPath);
        var objectsToDownload = assetIndex.objects().values().stream()
                .distinct() // The same object can be referenced multiple times
                .filter(obj -> Files.notExists(objectsFolder.resolve(getObjectPath(obj))))
                .toList();

        try (var downloader = new ParallelDownloader(downloadManager, concurrentDownloads, objectsFolder, objectsToDownload.size())) {
            if (copyLauncherAssets) {
                downloader.setLocalSources(launcherInstallations.getAssetRoots());
            }

            for (var object : objectsToDownload) {
                var spec = new AssetDownloadSpec(object);
                var objectHash = object.hash();
                String objectPath = objectHash.substring(0, 2) + "/" + objectHash;
                downloader.submitDownload(spec, objectPath);
            }
        } catch (DownloadsFailedException e) {
            System.err.println(e.getErrors().size() + " files failed to download");
            System.err.println("First error:");
            e.getErrors().getFirst().printStackTrace();
            return 1;
        }

        writeProperties(assetRoot, assetIndexReference.id());
        writeJson(assetRoot, assetIndexReference.id());
        return 0;
    }

    private String getMinecraftVersion(ArtifactManager artifactManager) throws IOException {
        if (version.minecraftVersion != null) {
            return version.minecraftVersion;
        }

        MavenCoordinate neoformArtifact;
        if (version.neoformArtifact != null) {
            neoformArtifact = MavenCoordinate.parse(version.neoformArtifact);
        } else {
            // Pull from neoforge artifact then
            var neoforgeArtifact = artifactManager.get(version.neoforgeArtifact);
            try (var neoforgeZipFile = new JarFile(neoforgeArtifact.path().toFile())) {
                var neoforgeConfig = NeoForgeConfig.from(neoforgeZipFile);
                neoformArtifact = MavenCoordinate.parse(neoforgeConfig.neoformArtifact());
            }
        }

        var neoFormArchive = artifactManager.get(neoformArtifact);
        try (var zipFile = new ZipFile(neoFormArchive.path().toFile())) {
            return NeoFormConfig.from(zipFile).minecraftVersion();
        }
    }

    private void writeProperties(Path assetRoot, String assetIndexId) throws IOException {
        if (outputPropertiesPath != null) {
            var properties = new Properties();
            properties.put("assets_root", assetRoot.toAbsolutePath().toString());
            properties.put("asset_index", assetIndexId);
            try (var out = new BufferedOutputStream(Files.newOutputStream(Paths.get(outputPropertiesPath)))) {
                properties.store(out, null);
            }
        }
    }

    private void writeJson(Path assetRoot, String assetIndexId) throws IOException {
        if (outputJsonPath != null) {
            var jsonObject = new JsonObject();
            jsonObject.addProperty("assets", assetRoot.toAbsolutePath().toString());
            jsonObject.addProperty("asset_index", assetIndexId);
            var jsonString = new Gson().toJson(jsonObject);
            Files.writeString(outputJsonPath, jsonString, StandardCharsets.UTF_8);
        }
    }

    private static String getObjectPath(AssetObject object) {
        var objectHash = object.hash();
        return objectHash.substring(0, 2) + "/" + objectHash;
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

    private class AssetDownloadSpec implements DownloadSpec {
        private final AssetObject object;

        public AssetDownloadSpec(AssetObject object) {
            this.object = object;
        }

        @Override
        public URI uri() {
            return URI.create(assetRepository.toString() + getObjectPath(object));
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
