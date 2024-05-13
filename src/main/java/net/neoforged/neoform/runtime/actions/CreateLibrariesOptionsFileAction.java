package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;

import java.io.IOException;
import java.nio.file.Files;

public class CreateLibrariesOptionsFileAction extends BuiltInAction implements ExecutionNodeAction {
    private final ExtensibleClasspath classpath = new ExtensibleClasspath();

    public CreateLibrariesOptionsFileAction() {
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        var effectiveClasspath = classpath.copy();
        effectiveClasspath.addMinecraftLibraries(versionManifest.libraries());
        var classpath = environment.getArtifactManager().resolveClasspath(effectiveClasspath.getEffectiveClasspath());

        var vineflowerArgs = classpath.stream().map(l -> "-e=" + l.toAbsolutePath()).toList();

        var libraryListFile = environment.getOutputPath("output");
        Files.write(libraryListFile, vineflowerArgs);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        classpath.computeCacheKey("compile classpath", ck);
    }

    public ExtensibleClasspath getClasspath() {
        return classpath;
    }
}
