package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import net.neoforged.neoform.runtime.utils.StringUtil;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand implements Callable<Integer> {
    private static final Logger LOG = Logger.create();

    private static final ThreadFactory DOWNLOAD_THREAD_FACTORY = r -> Thread.ofVirtual().name("download-asset", 1).unstarted(r);

    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.ArgGroup(multiplicity = "1")
    public Version version;

    @CommandLine.Option(names = "--asset-repository")
    public URI assetRepository = URI.create("https://resources.download.minecraft.net/");

    @CommandLine.Option(names = "--concurrent-downloads")
    public int concurrentDownloads = 25;

    /**
     * Properties file that will receive the metadata of the asset index.
     */
    @CommandLine.Option(names = "--output-properties-to")
    public String outputPropertiesPath;

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
    public Integer call() throws Exception {
        try (var downloadManager = new DownloadManager();
             var cacheManager = commonOptions.createCacheManager();
             var lockManager = commonOptions.createLockManager()) {

            var launcherInstallations = commonOptions.createLauncherInstallations();
            var artifactManager = commonOptions.createArtifactManager(cacheManager, downloadManager, lockManager, launcherInstallations);

            return downloadAssets(downloadManager, artifactManager, cacheManager);
        }
    }

    private int downloadAssets(DownloadManager downloadManager,
                               ArtifactManager artifactManager,
                               CacheManager cacheManager) throws IOException {

        var minecraftVersion = getMinecraftVersion(artifactManager);

        var versionManifest = MinecraftVersionManifest.from(artifactManager.getVersionManifest(minecraftVersion).path());
        var assetIndexReference = versionManifest.assetIndex();
        LOG.println("Downloading asset index " + assetIndexReference.id());

        var assetRoot = cacheManager.getAssetsDir();
        var indexFolder = assetRoot.resolve("indexes");
        Files.createDirectories(indexFolder);
        var objectsFolder = assetRoot.resolve("objects");
        Files.createDirectories(objectsFolder);

        var assetIndexPath = indexFolder.resolve(assetIndexReference.id() + ".json");
        downloadManager.download(assetIndexReference, assetIndexPath);

        // Pre-create all folders
        for (var i = 0; i < 256; i++) {
            var objectSubFolder = objectsFolder.resolve(HexFormat.of().toHexDigits(i, 2));
            Files.createDirectories(objectSubFolder);
        }

        AtomicInteger downloadsDone = new AtomicInteger();
        AtomicLong bytesDownloaded = new AtomicLong();
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
        LOG.println("Downloaded " + objectsToDownload.size() + " assets with a total size of " + StringUtil.formatBytes(bytesDownloaded.get()));

        if (!errors.isEmpty()) {
            System.err.println(errors.size() + " files failed to download");
            System.err.println("First error:");
            errors.getFirst().printStackTrace();
            return 1;
        }

        if (outputPropertiesPath != null) {
            var properties = new Properties();
            properties.put("assets_root", assetRoot.toAbsolutePath().toString());
            properties.put("asset_index", assetIndexReference.id());
            try (var out = new BufferedOutputStream(Files.newOutputStream(Paths.get(outputPropertiesPath)))) {
                properties.store(out, null);
            }
        }

        return 0;
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
