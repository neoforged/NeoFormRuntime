package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.artifacts.ArtifactManager;
import net.neoforged.neoforminabox.engine.ProcessingEnvironment;

import java.io.IOException;

public class DownloadLauncherManifestAction extends BuiltInAction {
    private final ArtifactManager artifactManager;

    public DownloadLauncherManifestAction(ArtifactManager artifactManager) {
        this.artifactManager = artifactManager;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException {
        var artifact = artifactManager.getLauncherManifest();
        environment.setOutput("output", artifact.path());
    }
}
