package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.manifests.LauncherManifest;
import net.neoforged.neoforminabox.manifests.MinecraftLibrary;
import net.neoforged.neoforminabox.manifests.MinecraftVersionManifest;
import net.neoforged.neoforminabox.utils.FilenameUtil;
import net.neoforged.neoforminabox.utils.MavenCoordinate;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

public class ArtifactManager {
    private final List<URI> repositoryBaseUrls;
    private final DownloadManager downloadManager;
    private final LockManager lockManager;
    private final URI launcherManifestUrl;
    private final Path artifactsCache;

    public ArtifactManager(List<URI> repositoryBaseUrls,
                           CacheManager cacheManager,
                           DownloadManager downloadManager,
                           LockManager lockManager,
                           URI launcherManifestUrl) {
        this.repositoryBaseUrls = repositoryBaseUrls;
        this.downloadManager = downloadManager;
        this.lockManager = lockManager;
        this.launcherManifestUrl = launcherManifestUrl;
        this.artifactsCache = cacheManager.getCacheDir().resolve("artifacts");
    }

    public Artifact get(MinecraftLibrary library) throws IOException {
        var artifact = library.getArtifactDownload();
        if (artifact == null) {
            throw new IllegalArgumentException("Cannot download a library that has no artifact defined: " + library);
        }

        // TODO: if we identify where the Minecraft installation is, we could try to copy the library from there

        var mavenCoordinate = MavenCoordinate.parse(library.artifactId());
        var finalLocation = artifactsCache.resolve(mavenCoordinate.toRelativeRepositoryPath());

        return download(finalLocation, artifact);
    }

    public Artifact get(String location, URI repositoryBaseUrl) throws IOException {
        var mavenCoordinate = MavenCoordinate.parse(location);

        var finalLocation = artifactsCache.resolve(mavenCoordinate.toRelativeRepositoryPath());
        var url = mavenCoordinate.toRepositoryUri(repositoryBaseUrl);

        return download(finalLocation, url);
    }

    public Artifact get(String location) throws IOException {
        var mavenCoordinate = MavenCoordinate.parse(location);

        var finalLocation = artifactsCache.resolve(mavenCoordinate.toRelativeRepositoryPath());

        return download(finalLocation, () -> {
            for (var repositoryBaseUrl : repositoryBaseUrls) {
                var url = mavenCoordinate.toRepositoryUri(repositoryBaseUrl);
                try {
                    downloadManager.download(url, finalLocation);
                    return;
                } catch (FileNotFoundException ignored) {
                }
            }

            throw new FileNotFoundException("Could not find " + mavenCoordinate + " in any repository.");
        });
    }

    /**
     * Special purpose method to get the version manifest for a specific Minecraft version.
     */
    public Artifact getVersionManifest(String minecraftVersion) throws IOException {
        var finalLocation = artifactsCache.resolve("minecraft_" + minecraftVersion + "_version_manifest.json");
        return download(finalLocation, () -> {
            var launcherManifestArtifact = getLauncherManifest();
            var launcherManifest = LauncherManifest.from(launcherManifestArtifact.path());

            var versionEntry = launcherManifest.versions().stream()
                    .filter(v -> minecraftVersion.equals(v.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Could not find Minecraft version: " + minecraftVersion));

            downloadManager.download(versionEntry.url(), finalLocation);
        });
    }

    /**
     * Gets the v2 Launcher Manifest.
     */
    public Artifact getLauncherManifest() throws IOException {
        var finalLocation = artifactsCache.resolve("minecraft_launcher_manifest.json");
        return download(finalLocation, launcherManifestUrl);
    }

    /**
     * Download a file declared in the Minecraft version manifest.
     */
    public Artifact downloadFromManifest(MinecraftVersionManifest versionManifest, String type) throws IOException {
        var downloadSpec = versionManifest.downloads().get(type);
        if (downloadSpec == null) {
            throw new IllegalArgumentException("Minecraft version manifest " + versionManifest.id()
                                               + " does not declare a download for " + type + ". Available: "
                                               + versionManifest.downloads().keySet());
        }

        var extension = FilenameUtil.getExtension(downloadSpec.uri().getPath());
        var finalPath = artifactsCache.resolve("minecraft_" + versionManifest.id() + "_" + type + extension);

        return download(finalPath, downloadSpec);
    }

    @FunctionalInterface
    public interface DownloadAction {
        void run() throws IOException;
    }

    private Artifact download(Path finalLocation, DownloadAction downloadAction) throws IOException {
        // Check if it already exists
        var attributeView = Files.getFileAttributeView(finalLocation, BasicFileAttributeView.class);
        try {
            attributeView.setTimes(null, FileTime.from(Instant.now()), null);
        } catch (NoSuchFileException e) {
            // Artifact does not exist, try to get it
            var lockKey = finalLocation.toAbsolutePath().normalize().toString();
            try (var ignored = lockManager.lock(lockKey)) {
                // Re-check (essentially double-checked locking)
                if (!Files.exists(finalLocation)) {
                    downloadAction.run();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var attributes = attributeView.readAttributes();
        if (!attributes.isRegularFile()) {
            throw new IOException("Corrupted artifact: " + finalLocation + ". Expected a file.");
        }

        return new Artifact(finalLocation, attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    private Artifact download(Path finalLocation, URI uri) throws IOException {
        return download(finalLocation, new SimpleDownloadSpec(uri));
    }

    private Artifact download(Path finalLocation, DownloadSpec spec) throws IOException {
        return download(finalLocation, () -> downloadManager.download(spec, finalLocation));
    }
}
