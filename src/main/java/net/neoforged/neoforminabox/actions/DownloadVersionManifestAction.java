package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cli.ArtifactManager;
import net.neoforged.neoforminabox.cli.ProcessingEnvironment;
import net.neoforged.neoforminabox.config.neoform.NeoFormDistConfig;

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
