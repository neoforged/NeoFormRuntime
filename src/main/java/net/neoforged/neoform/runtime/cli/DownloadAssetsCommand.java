package net.neoforged.neoform.runtime.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.OsUtil;
import net.neoforged.neoform.runtime.utils.StringUtil;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand extends NeoFormEngineCommand {
    private static final Logger LOG = Logger.create();

    private static final ThreadFactory DOWNLOAD_THREAD_FACTORY = r -> Thread.ofVirtual().name("download-asset", 1).unstarted(r);

    @CommandLine.ArgGroup(multiplicity = "1")
    public Version version;

    @CommandLine.Option(names = "--asset-repository")
    public URI assetRepository = URI.create("https://resources.download.minecraft.net/");

    @CommandLine.Option(names = "--reuse-launcher-assets", description = "Try to find the Minecraft Launcher in common locations and reuse its assets")
    public boolean reuseLauncherAssets = true;

    @CommandLine.Option(names = "--concurrent-downloads")
    public int concurrentDownloads = 25;

    /**
     * Properties file that will receive the metadata of the asset index.
     */
    @CommandLine.Option(names = "--output-properties-to")
    public String outputPropertiesPath;

    /**
     * Write a JSON file as it is used by the Neoform Start.java file
     * See:
     * https://github.com/neoforged/NeoForm/blob/c2f5c5eda5eeca2e554c51872c28d0e68bc244bc/versions/release/1.21/inject/mcp/client/Start.java
     */
    @CommandLine.Option(names = "--output-json-to")
    public String outputJsonPath;

    public static class Version {
        @CommandLine.Option(names = "--minecraft-version")
        String minecraftVersion;
        @CommandLine.Option(names = "--neoform")
        String neoformArtifact;
        @CommandLine.Option(names = "--neoforge")
        String neoforgeArtifact;
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

    @Override
    protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closeables) throws IOException {
        var artifactManager = engine.getArtifactManager();
        var downloadManager = artifactManager.getDownloadManager();
        var minecraftVersion = getMinecraftVersion(artifactManager);

        var versionManifest = MinecraftVersionManifest.from(artifactManager.getVersionManifest(minecraftVersion).path());
        var assetIndexReference = versionManifest.assetIndex();
        LOG.println("Downloading asset index " + assetIndexReference.id());

        var cacheManager = engine.getCacheManager();
        var assetRoot = cacheManager.getAssetsDir();
        var indexFolder = assetRoot.resolve("indexes");
        Files.createDirectories(indexFolder);
        var objectsFolder = assetRoot.resolve("objects");
        Files.createDirectories(objectsFolder);

        // Find the local Minecraft Launcher asset object folder to copy assets over from
        Path launcherAssetsObjectRoot = getLauncherAssetObjectRoot();
        if (launcherAssetsObjectRoot != null) {
            if (!launcherAssetsObjectRoot.equals(assetRoot)) {
                LOG.println("Found Launcher asset objects at " + launcherAssetsObjectRoot);
            } else {
                // User has set the asset download dir TO the launcher directory, which is valid
                // but in that case do not try to copy the files over themselves.
                launcherAssetsObjectRoot = null;
            }
        }

        var assetIndexPath = indexFolder.resolve(assetIndexReference.id() + ".json");
        downloadManager.download(assetIndexReference, assetIndexPath);

        // Pre-create all folders
        for (var i = 0; i < 256; i++) {
            var objectSubFolder = objectsFolder.resolve(HexFormat.of().toHexDigits(i, 2));
            Files.createDirectories(objectSubFolder);
        }

        AtomicInteger downloadsDone = new AtomicInteger();
        AtomicInteger copiesDone = new AtomicInteger();
        AtomicLong bytesDownloaded = new AtomicLong();
        AtomicLong bytesCopied = new AtomicLong();
        var errors = new ArrayList<Exception>();
        var assetIndex = AssetIndex.from(assetIndexPath);
        // The same object can be referenced multiple times
        var objectsToDownload = assetIndex.objects().values().stream()
                .distinct()
                .filter(obj -> Files.notExists(objectsFolder.resolve(getObjectPath(obj))))
                .toList();

        if (concurrentDownloads < 1) {
            throw new IllegalStateException("Cannot set concurrent downloads to less than 1: " + concurrentDownloads);
        }

        var semaphore = new Semaphore(concurrentDownloads);
        try (var executor = Executors.newThreadPerTaskExecutor(DOWNLOAD_THREAD_FACTORY)) {
            for (var object : objectsToDownload) {
                var spec = new AssetDownloadSpec(object);
                var objectHash = object.hash();
                String objectPath = objectHash.substring(0, 2) + "/" + objectHash;
                var objectLocation = objectsFolder.resolve(objectPath);
                executor.execute(() -> {
                    boolean hasAcquired = false;
                    try {
                        semaphore.acquire();
                        hasAcquired = true;

                        // Check if the object may exist already
                        if (launcherAssetsObjectRoot != null) {
                            var existingFile = launcherAssetsObjectRoot.resolve(objectPath);
                            if (Files.isRegularFile(existingFile) && Files.size(existingFile) == spec.size()) {
                                FileUtil.safeCopy(existingFile, objectLocation);
                                bytesCopied.addAndGet(Files.size(objectLocation));
                                copiesDone.incrementAndGet();
                                return;
                            }
                        }

                        if (downloadManager.download(spec, objectLocation, true)) {
                            bytesDownloaded.addAndGet(spec.size());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    } finally {
                        if (hasAcquired) {
                            semaphore.release();
                        }
                        var finished = downloadsDone.incrementAndGet();
                        if (finished % 100 == 0) {
                            LOG.println(finished + "/" + objectsToDownload.size() + " downloads");
                        }
                    }
                });
            }
        }

        LOG.println("Downloaded " + downloadsDone.get() + " assets with a total size of " + StringUtil.formatBytes(bytesDownloaded.get()));
        if (launcherAssetsObjectRoot != null) {
            LOG.println("Copied " + copiesDone.get() + " assets with a total size of " + StringUtil.formatBytes(bytesCopied.get()));
        }

        if (!errors.isEmpty()) {
            System.err.println(errors.size() + " files failed to download");
            System.err.println("First error:");
            errors.getFirst().printStackTrace();
            System.exit(1);
        }

        writeProperties(assetRoot, assetIndexReference.id());
        writeJson(assetRoot, assetIndexReference.id());
    }

    private void writeProperties(Path assetRoot, String assetIndexId) throws IOException {
        if (outputPropertiesPath != null) {
            var properties = new Properties();
            properties.put("assets_root", assetRoot.toAbsolutePath().toString());
            properties.put("asset_index", assetIndexId);
            try (var writer = Files.newBufferedWriter(Paths.get(outputPropertiesPath), StandardOpenOption.CREATE)) {
                properties.store(writer, null);
            }
        }
    }

    private void writeJson(Path assetRoot, String id) throws IOException {
        if (outputJsonPath != null) {
            var jsonObject = new JsonObject();
            jsonObject.addProperty("assets", assetRoot.toAbsolutePath().toString());
            jsonObject.addProperty("asset_index", id);
            var jsonString = new Gson().toJson(jsonObject);
            Files.writeString(Paths.get(outputJsonPath), jsonString, StandardCharsets.UTF_8);
        }
    }

    @Nullable
    private Path getLauncherAssetObjectRoot() {
        if (!reuseLauncherAssets) {
            return null;
        }

        // Relevant for Windows
        if (OsUtil.isWindows()) {
            try {
                var appData = System.getenv("APPDATA");
                if (appData != null) {
                    var objectsRoot = Paths.get(appData, ".minecraft/assets/objects");
                    if (Files.isDirectory(objectsRoot)) {
                        return objectsRoot;
                    }
                }
            } catch (Exception ignore) {
            }
        }

        try {
            var objectsRoot = Paths.get(System.getProperty("user.home"), ".minecraft/assets/objects");
            if (Files.isDirectory(objectsRoot)) {
                return objectsRoot;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String getObjectPath(AssetObject object) {
        var objectHash = object.hash();
        return objectHash.substring(0, 2) + "/" + objectHash;
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
