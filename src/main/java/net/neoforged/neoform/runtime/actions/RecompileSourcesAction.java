package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
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
    private int targetJavaVersion = 21;

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        classpath.computeCacheKey("compile classpath", ck);
        sourcepath.computeCacheKey("compile sourcepath", ck);
        ck.add("target java version", String.valueOf(targetJavaVersion));
    }

    protected final List<Path> getEffectiveClasspath(ProcessingEnvironment environment) throws IOException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        var effectiveClasspathItems = classpath.mergeWithMinecraftLibraries(versionManifest).getEffectiveClasspath();

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

    public int getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public void setTargetJavaVersion(int targetJavaVersion) {
        this.targetJavaVersion = targetJavaVersion;
    }
}
