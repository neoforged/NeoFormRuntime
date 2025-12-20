package net.neoforged.neoform.runtime.artifacts;

import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.cli.LockManager;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.manifests.LauncherManifest;
import net.neoforged.neoform.runtime.manifests.MinecraftLibrary;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.FilenameUtil;
import net.neoforged.neoform.runtime.utils.HashingUtil;
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
import java.util.Objects;
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
    private final LauncherInstallations launcherInstallations;

    public ArtifactManager(List<URI> repositoryBaseUrls,
                           CacheManager cacheManager,
                           DownloadManager downloadManager,
                           LockManager lockManager,
                           URI launcherManifestUrl,
                           LauncherInstallations launcherInstallations) {
        this.repositoryBaseUrls = repositoryBaseUrls;
        this.downloadManager = downloadManager;
        this.lockManager = lockManager;
        this.launcherManifestUrl = launcherManifestUrl;
        this.artifactsCache = cacheManager.getArtifactCacheDir();
        this.launcherInstallations = launcherInstallations;
    }

    public Artifact get(MinecraftLibrary library) throws IOException {
        var artifact = library.getArtifactDownload();
        if (artifact == null) {
            throw new IllegalArgumentException("Cannot download a library that has no artifact defined: " + library);
        }

        var artifactCoordinate = library.getMavenCoordinate();
        var externalArtifact = getFromExternalManifest(artifactCoordinate);
        if (externalArtifact != null) {
            return externalArtifact;
        }

        var relativePath = artifactCoordinate.toRelativeRepositoryPath();

        // Try reusing it from a local Minecraft installation, which ultimately is structured like a Maven repo
        var localMinecraftLibraries = new ArrayList<>(launcherInstallations.getInstallationRoots());
        for (var localRepo : localMinecraftLibraries) {
            var localPath = localRepo.resolve("libraries").resolve(relativePath);
            try {
                // Ensure the file matches before using it
                var fileHash = HashingUtil.hashFile(localPath, artifact.checksumAlgorithm());
                if (Objects.equals(fileHash, artifact.checksum())) {
                    return Artifact.ofPath(localPath);
                }
            } catch (IOException ignored) {
                // Ignore if it doesn't exist or is otherwise fails to be read
            }
        }

        var finalLocation = artifactsCache.resolve(relativePath);

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

        // Yet another special case: dynamic versions!
        // Used in 1.12.1, for example. And yes, this will be very slow.
        if (mavenCoordinate.isDynamicVersion()) {
            var availableVersions = MavenMetadata.gatherVersions(
                    downloadManager,
                    repositoryBaseUrls,
                    mavenCoordinate.groupId(),
                    mavenCoordinate.artifactId()
            );
            for (var availableVersion : availableVersions) {
                if (mavenCoordinate.matchesVersion(availableVersion.version())) {
                    var concreteMavenCoordinate = mavenCoordinate.withVersion(availableVersion.version());
                    return get(concreteMavenCoordinate, availableVersion.repositoryUrl());
                }
            }
        }

        var finalLocation = artifactsCache.resolve(mavenCoordinate.toRelativeRepositoryPath());

        // Special case: NeoForge reference libraries that are only available via the Mojang download server
        if ((mavenCoordinate.groupId().equals("com.mojang") && mavenCoordinate.artifactId().equals("logging"))
            || (mavenCoordinate.groupId().equals("net.minecraft") && mavenCoordinate.artifactId().equals("launchwrapper"))) {
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
                case ClasspathItem.NodeOutputItem(NodeOutput output) -> output.getResultPath();
            };
            result.add(pathToAdd);
        }
        return result;
    }

    /**
     * Special purpose method to get the version manifest for a specific Minecraft version.
     */
    public Artifact getVersionManifest(String minecraftVersion) throws IOException {
        // Check local Minecraft launchers for a copy of it
        for (var root : launcherInstallations.getInstallationRoots()) {
            var localPath = root.resolve("versions").resolve(minecraftVersion).resolve(minecraftVersion + ".json");
            if (Files.isReadable(localPath)) {
                return Artifact.ofPath(localPath);
            }
        }

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

        // Note that we're not reusing launcher manifests from known launcher installations,
        // since we don't know how old they are

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
                var parse = MavenCoordinate.parse(artifactId);
                externallyProvided.put(parse, getArtifactFromPath(value));
            } catch (Exception e) {
                System.err.println("Failed to pre-load artifact '" + artifactId + "' from path '" + value + "': " + e);
                System.exit(1);
            }
        }
        LOG.println("Loaded " + properties.size() + " artifacts from " + artifactManifestPath);
    }

    private Artifact getArtifactFromPath(String path) throws IOException {
        return Artifact.ofPath(Paths.get(path));
    }

    @FunctionalInterface
    public interface DownloadAction {
        void run() throws IOException;
    }

    private Artifact getFromExternalManifest(MavenCoordinate artifactCoordinate) {
        // Try direct match first
        var artifact = externallyProvided.get(artifactCoordinate);
        if (artifact != null) {
            return artifact;
        }

        // Find any manifest entry for the same group/artifact/classifier and evaluate if it matches a dynamic version constraint
        if (artifactCoordinate.isDynamicVersion()) {
            for (var entry : externallyProvided.entrySet()) {
                if (artifactCoordinate.equalsWithoutVersion(entry.getKey())
                        && artifactCoordinate.matchesVersion(entry.getKey().version())) {
                    return entry.getValue();
                }
            }
        }

        // Fall back to looking up a wildcard version for dependency replacement in includeBuild scenarios
        if (!"*".equals(artifactCoordinate.version())) {
            artifact = externallyProvided.get(artifactCoordinate.withVersion("*"));
            if (artifact != null) {
                return artifact;
            }
        }

        if (warnOnArtifactManifestMiss && !externallyProvided.isEmpty()) {
            LOG.warn(artifactCoordinate + " is not present in the artifact manifest");
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

    public void setWarnOnArtifactManifestMiss(boolean warnOnArtifactManifestMiss) {
        this.warnOnArtifactManifestMiss = warnOnArtifactManifestMiss;
    }
}
