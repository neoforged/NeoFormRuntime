package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.artifacts.ArtifactManager;
import net.neoforged.neoforminabox.engine.ProcessingEnvironment;
import net.neoforged.neoforminabox.graph.ResultRepresentation;

import java.io.IOException;

/**
 * Downloads one of the main files (executables, mappings) found in the Minecraft Version Manifest.
 */
public class DownloadFromVersionManifestAction extends BuiltInAction {
    private final ArtifactManager artifactManager;
    private final String manifestEntry;

    public DownloadFromVersionManifestAction(ArtifactManager artifactManager, String manifestEntry) {
        this.artifactManager = artifactManager;
        this.manifestEntry = manifestEntry;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);
        var result = artifactManager.downloadFromManifest(versionManifest, manifestEntry);
        environment.setOutput("output", result.path());
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.add("manifest entry", manifestEntry);
    }
}
