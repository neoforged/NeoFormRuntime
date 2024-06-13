package net.neoforged.neoform.runtime.artifacts;

import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cli.LockManager;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.manifests.LauncherManifest;
import net.neoforged.neoform.runtime.manifests.MinecraftLibrary;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.FilenameUtil;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ArtifactManager {
    private static final Logger LOG = Logger.create();

    private static final URI MINECRAFT_LIBRARIES_URI = URI.create("https://libraries.minecraft.net");
    private final List<URI> repositoryBaseUrls;
    private final DownloadManager downloadManager;
    private final LockManager lockManager;
    private final URI launcherManifestUrl;
    private final Path artifactsCache;
    private final Map<MavenCoordinate, Artifact> externallyProvided = new HashMap<>();
    private boolean warnOnArtifactManifestMiss;

    public ArtifactManager(List<URI> repositoryBaseUrls,
                           CacheManager cacheManager,
                           DownloadManager downloadManager,
                           LockManager lockManager,
                           URI launcherManifestUrl) {
        this.repositoryBaseUrls = repositoryBaseUrls;
        this.downloadManager = downloadManager;
        this.lockManager = lockManager;
        this.launcherManifestUrl = launcherManifestUrl;
        this.artifactsCache = cacheManager.getArtifactCacheDir();
    }

    public Artifact get(MinecraftLibrary library) throws IOException {
        var artifact = library.getArtifactDownload();
        if (artifact == null) {
            throw new IllegalArgumentException("Cannot download a library that has no artifact defined: " + library);
        }

        // TODO: if we identify where the Minecraft installation is, we could try to copy the library from there

        var artifactCoordinate = MavenCoordinate.parse(library.artifactId());
        var externalArtifact = getFromExternalManifest(artifactCoordinate);
        if (externalArtifact != null) {
            return externalArtifact;
        }

        var finalLocation = artifactsCache.resolve(artifactCoordinate.toRelativeRepositoryPath());

        return download(finalLocation, artifact);
    }

    public Artifact get(String location, URI repositoryBaseUrl) throws IOException {
        return get(MavenCoordinate.parse(location), repositoryBaseUrl);
    }

    public Artifact get(MavenCoordinate artifactCoordinate, URI repositoryBaseUrl) throws IOException {
        var externalArtifact = getFromExternalManifest(artifactCoordinate);
        if (externalArtifact != null) {
            return externalArtifact;
        }

        var finalLocation = artifactsCache.resolve(artifactCoordinate.toRelativeRepositoryPath());
        var url = artifactCoordinate.toRepositoryUri(repositoryBaseUrl);
        return download(finalLocation, url);
    }

    public Artifact get(String location) throws IOException {
        MavenCoordinate coordinate;
        try {
            coordinate = MavenCoordinate.parse(location);
        } catch (IllegalArgumentException ignored) {
            return getArtifactFromPath(location);
        }

        return get(coordinate);
    }

    public Artifact get(MavenCoordinate mavenCoordinate) throws IOException {
        var externalArtifact = getFromExternalManifest(mavenCoordinate);
        if (externalArtifact != null) {
            return externalArtifact;
        }

        var finalLocation = artifactsCache.resolve(mavenCoordinate.toRelativeRepositoryPath());

        // Special case: NeoForge reference libraries that are only available via the Mojang download server
        if (mavenCoordinate.groupId().equals("com.mojang") && mavenCoordinate.artifactId().equals("logging")) {
            return get(mavenCoordinate, MINECRAFT_LIBRARIES_URI);
        }

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

    public List<Path> resolveClasspath(Collection<ClasspathItem> classpathItems) throws IOException {
        var result = new ArrayList<Path>(classpathItems.size());
        for (var item : classpathItems) {
            Path pathToAdd = switch (item) {
                case ClasspathItem.MavenCoordinateItem(MavenCoordinate mavenCoordinate, URI repositoryUri) -> {
                    if (repositoryUri == null) {
                        yield get(mavenCoordinate).path();
                    } else {
                        yield get(mavenCoordinate, repositoryUri).path();
                    }
                }
                case ClasspathItem.MinecraftLibraryItem(MinecraftLibrary library) -> get(library).path();
                case ClasspathItem.PathItem(Path path) -> path;
            };
            result.add(pathToAdd);
        }
        return result;
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

        downloadManager.download(DownloadSpec.of(launcherManifestUrl), finalLocation);

        return new Artifact(
                finalLocation,
                Files.getLastModifiedTime(finalLocation).toMillis(),
                Files.size(finalLocation)
        );
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

    public void loadArtifactManifest(Path artifactManifestPath) throws IOException {

        var properties = new Properties();
        try (var in = Files.newInputStream(artifactManifestPath)) {
            properties.load(in);
        }

        for (var artifactId : properties.stringPropertyNames()) {
            var value = properties.getProperty(artifactId);
            try {
                var parse = normalizeExtension(MavenCoordinate.parse(artifactId));
                externallyProvided.put(parse, getArtifactFromPath(value));
            } catch (Exception e) {
                System.err.println("Failed to pre-load artifact '" + artifactId + "' from path '" + value + "': " + e);
                System.exit(1);
            }
        }
        LOG.println("Loaded " + properties.size() + " artifacts from " + artifactManifestPath);
    }

    private Artifact getArtifactFromPath(String path) throws IOException {
        return getArtifactFromPath(Paths.get(path));
    }

    private Artifact getArtifactFromPath(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
        var attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
        return new Artifact(path, attrView.lastModifiedTime().toMillis(), attrView.size());
    }

    public DownloadManager getDownloadManager() {
        return downloadManager;
    }

    @FunctionalInterface
    public interface DownloadAction {
        void run() throws IOException;
    }

    private Artifact getFromExternalManifest(MavenCoordinate artifactCoordinate) {
        artifactCoordinate = normalizeExtension(artifactCoordinate);

        var artifact = externallyProvided.get(artifactCoordinate);
        if (artifact != null) {
            return artifact;
        }

        if (warnOnArtifactManifestMiss && !externallyProvided.isEmpty()) {
            LOG.println("  " + AnsiColor.YELLOW + "WARNING: " + AnsiColor.RESET + artifactCoordinate + " is not present in the artifact manifest");
        }
        return null;
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
        return download(finalLocation, DownloadSpec.of(uri));
    }

    private Artifact download(Path finalLocation, DownloadSpec spec) throws IOException {
        return download(finalLocation, () -> downloadManager.download(spec, finalLocation));
    }

    public boolean isWarnOnArtifactManifestMiss() {
        return warnOnArtifactManifestMiss;
    }

    public void setWarnOnArtifactManifestMiss(boolean warnOnArtifactManifestMiss) {
        this.warnOnArtifactManifestMiss = warnOnArtifactManifestMiss;
    }

    // Normalize "jar" extensions to "" since they're the default
    private static MavenCoordinate normalizeExtension(MavenCoordinate coordinate) {
        if (coordinate.extension().equals("jar")) {
            return new MavenCoordinate(
                    coordinate.groupId(),
                    coordinate.artifactId(),
                    "",
                    coordinate.classifier(),
                    coordinate.version()
            );
        }
        return coordinate;
    }
}
