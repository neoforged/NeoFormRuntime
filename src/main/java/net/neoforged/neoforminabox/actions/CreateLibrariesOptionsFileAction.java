package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.artifacts.ClasspathItem;
import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.engine.ProcessingEnvironment;
import net.neoforged.neoforminabox.graph.ResultRepresentation;
import net.neoforged.neoforminabox.manifests.MinecraftLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CreateLibrariesOptionsFileAction extends BuiltInAction implements ActionWithClasspath {
    private final List<ClasspathItem> additionalClasspath = new ArrayList<>();

    public CreateLibrariesOptionsFileAction() {
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        var combinedClasspath = new ArrayList<ClasspathItem>();

        // Add all libraries from the manifest
        versionManifest.libraries()
                .stream()
                .filter(MinecraftLibrary::rulesMatch)
                .filter(library -> library.downloads().artifact() != null)
                .map(ClasspathItem::of)
                .forEach(combinedClasspath::add);

        combinedClasspath.addAll(additionalClasspath);

        var classpath = environment.getArtifactManager().resolveClasspath(combinedClasspath);

        var vineflowerArgs = classpath.stream().map(l -> "-e=" + l.toAbsolutePath()).toList();

        var libraryListFile = environment.getOutputPath("output");
        Files.write(libraryListFile, vineflowerArgs);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.add("additional classpath", additionalClasspath.stream().map(Objects::toString).collect(Collectors.joining(", ")));
    }

    @Override
    public List<ClasspathItem> getClasspath() {
        return additionalClasspath;
    }
}
