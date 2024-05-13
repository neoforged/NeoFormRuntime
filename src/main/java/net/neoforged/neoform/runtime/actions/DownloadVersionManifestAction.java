package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.config.neoform.NeoFormDistConfig;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.IOException;

public class DownloadVersionManifestAction extends BuiltInAction {
    private final ArtifactManager artifactManager;
    private final NeoFormDistConfig config;

    public DownloadVersionManifestAction(ArtifactManager artifactManager, NeoFormDistConfig config) {
        this.artifactManager = artifactManager;
        this.config = config;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var artifact = artifactManager.getVersionManifest(config.minecraftVersion());
        environment.setOutput("output", artifact.path());
    }
}
