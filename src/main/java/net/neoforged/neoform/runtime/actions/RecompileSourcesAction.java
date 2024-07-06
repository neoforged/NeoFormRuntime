package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public abstract class RecompileSourcesAction extends BuiltInAction implements ExecutionNodeAction {
    private final ExtensibleClasspath classpath = new ExtensibleClasspath();
    private final ExtensibleClasspath sourcepath = new ExtensibleClasspath();

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        classpath.computeCacheKey("compile classpath", ck);
        sourcepath.computeCacheKey("compile sourcepath", ck);
    }

    protected final List<Path> getEffectiveClasspath(ProcessingEnvironment environment) throws IOException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        // Merge the original Minecraft classpath with the libs required by additional patches that we made
        var effectiveClasspath = classpath.copy();
        effectiveClasspath.addMinecraftLibraries(versionManifest.libraries());
        var effectiveClasspathItems = effectiveClasspath.getEffectiveClasspath();

        var classpath = environment.getArtifactManager().resolveClasspath(effectiveClasspathItems);

        LOG.println(" " + classpath.size() + " items on the compile classpath");

        return classpath;
    }

    protected final List<Path> getEffectiveSourcepath(ProcessingEnvironment environment) throws IOException {
        var effectiveClasspath = sourcepath.copy();
        var effectiveItems = effectiveClasspath.getEffectiveClasspath();

        var sourcepath = environment.getArtifactManager().resolveClasspath(effectiveItems);

        LOG.println(" " + sourcepath.size() + " items on the sourcepath");

        return sourcepath;
    }

    public ExtensibleClasspath getClasspath() {
        return classpath;
    }

    public ExtensibleClasspath getSourcepath() {
        return sourcepath;
    }
}
