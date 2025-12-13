package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.FileUtil;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @see ArtifactManager
 */
@CommandLine.Command(name = "download-artifacts", description = "Downloads an artifact declared by the Minecraft version (such as the original client, server or mappings)")
public class DownloadArtifactsCommand extends MinecraftCommand {
    @CommandLine.Option(names = "--write-artifact", arity = "*")
    List<String> writeArtifacts = new ArrayList<>();

    @CommandLine.Option(names = "--write-version-manifest", description = "Downloads and writes the version manifest to the given location")
    @Nullable
    Path writeVersionManifest;

    @Override
    protected int runMinecraftCommand(DownloadManager downloadManager,
                                      CacheManager cacheManager,
                                      LockManager lockManager,
                                      ArtifactManager artifactManager,
                                      LauncherInstallations launcherInstallations,
                                      String minecraftVersion) throws IOException {

        var neededArtifacts = writeArtifacts.stream().<String[]>map(encodedResult -> {
                    var parts = encodedResult.split(":", 2);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("Specify an artifact destination in the form: <artifactId>:<destination>");
                    }
                    return parts;
                })
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> Paths.get(parts[1])
                ));

        // Grab the version manifest first
        var versionManifestPath = artifactManager.getVersionManifest(minecraftVersion).path();
        var versionManifest = MinecraftVersionManifest.from(versionManifestPath);

        // Then download any requested artifacts
        for (var entry : neededArtifacts.entrySet()) {
            var artifact = artifactManager.downloadFromManifest(versionManifest, entry.getKey());
            FileUtil.safeCopy(artifact.path(), entry.getValue());
        }

        if (writeVersionManifest != null) {
            FileUtil.safeCopy(versionManifestPath, writeVersionManifest);
        }

        return 0;
    }
}
