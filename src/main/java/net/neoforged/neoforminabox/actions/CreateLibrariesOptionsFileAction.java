package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cli.ArtifactManager;
import net.neoforged.neoforminabox.cli.ProcessingEnvironment;
import net.neoforged.neoforminabox.config.neoform.NeoFormDistConfig;
import net.neoforged.neoforminabox.graph.ResultRepresentation;
import net.neoforged.neoforminabox.manifests.MinecraftLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class CreateLibrariesOptionsFileAction extends BuiltInAction {
    private final ArtifactManager artifactManager;
    private final NeoFormDistConfig config;

    public CreateLibrariesOptionsFileAction(ArtifactManager artifactManager, NeoFormDistConfig config) {
        this.artifactManager = artifactManager;
        this.config = config;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);
        var libraries = versionManifest.libraries()
                .stream()
                .filter(MinecraftLibrary::rulesMatch)
                .filter(library -> library.downloads().artifact() != null)
                .collect(Collectors.toSet());

        var lines = new ArrayList<String>();
        for (var library : libraries) {
            var artifact = artifactManager.get(library);
            lines.add("-e=" + artifact.path().toAbsolutePath());
        }

        // Add libraries added by neoform
        for (var artifactId : config.libraries()) {
            var artifact = artifactManager.get(artifactId);
            lines.add("-e=" + artifact.path().toAbsolutePath());
        }

        var libraryListFile = environment.getOutputPath("output");
        Files.write(libraryListFile, lines);
    }
}
