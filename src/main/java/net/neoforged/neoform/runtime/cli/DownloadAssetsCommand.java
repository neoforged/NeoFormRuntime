package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.config.neoforge.NeoForgeConfig;
import net.neoforged.neoform.runtime.config.neoform.NeoFormConfig;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
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
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

@CommandLine.Command(name = "download-assets", description = "Download the client assets used to run a particular game version")
public class DownloadAssetsCommand extends NeoFormEngineCommand {
    private static final ThreadFactory DOWNLOAD_THREAD_FACTORY = r -> Thread.ofVirtual().name("download-asset", 1).unstarted(r);

    @CommandLine.ArgGroup(multiplicity = "1")
    public Version version;

    @CommandLine.Option(names = "--asset-repository")
    public URI assetRepository = URI.create("https://resources.download.minecraft.net/");

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
    protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
        var artifactManager = engine.getArtifactManager();
        var downloadManager = artifactManager.getDownloadManager();
        var minecraftVersion = getMinecraftVersion(artifactManager);

        var versionManifest = MinecraftVersionManifest.from(artifactManager.getVersionManifest(minecraftVersion).path());
        var assetIndexReference = versionManifest.assetIndex();
        System.out.println("Downloading asset index " + assetIndexReference.id());

        var assetRoot = engine.getHome().resolve("assets");
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
        var errors = new ArrayList<Exception>();
        var assetIndex = AssetIndex.from(assetIndexPath);
        // The same object can be referenced multiple times
        var objectsToDownload = assetIndex.objects().values().stream()
                .distinct()
                .filter(obj -> Files.notExists(objectsFolder.resolve(getObjectPath(obj))))
                .toList();

        // At most 50 concurrent downloads
        var semaphore = new Semaphore(50);
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
                        downloadManager.download(spec, objectLocation, true);
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
                            System.out.println(finished + "/" + objectsToDownload.size() + " downloads");
                        }
                    }
                });
            }
        }
        System.out.println(downloadsDone.get() + "/" + objectsToDownload.size() + " downloads");

        if (!errors.isEmpty()) {
            System.err.println(errors.size() + " files failed to download");
            System.err.println("First error:");
            errors.getFirst().printStackTrace();
            System.exit(1);
        }

        if (outputPropertiesPath != null) {
            var properties = new Properties();
            properties.put("assets_root", assetRoot.toAbsolutePath().toString());
            properties.put("asset_index", assetIndexReference.id());
            try (var writer = Files.newBufferedWriter(Paths.get(outputPropertiesPath), StandardOpenOption.CREATE)) {
                properties.store(writer, null);
            }
        }
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
