package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ClasspathConfiguration;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public abstract class RecompileSourcesAction extends BuiltInAction implements ExecutionNodeAction {

    private final ClasspathConfiguration classpathConfig;
    private int targetJavaVersion = 21;

    protected RecompileSourcesAction(ClasspathConfiguration classpathConfig) {
        this.classpathConfig = Objects.requireNonNull(classpathConfig, "classpathConfig");
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        classpathConfig.computeCacheKey("compile classpath", ck);
        ck.add("target java version", String.valueOf(targetJavaVersion));
    }

    protected final List<Path> getEffectiveClasspath(ProcessingEnvironment environment) throws IOException {
        var classpath = classpathConfig.resolve(environment);

        LOG.println(" " + classpath.size() + " items on the compile classpath");

        return classpath;
    }

    protected final List<Path> getEffectiveSourcepath(ProcessingEnvironment environment) throws IOException {
        var additionalSourcepath = environment.getInputPath("additionalSourcepath");
        var sourcepath = additionalSourcepath == null ? List.<Path>of() : List.of(additionalSourcepath);

        LOG.println(" " + sourcepath.size() + " items on the sourcepath");

        return sourcepath;
    }

    public ClasspathConfiguration getClasspath() {
        return classpathConfig;
    }

    public int getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public void setTargetJavaVersion(int targetJavaVersion) {
        this.targetJavaVersion = targetJavaVersion;
    }
}
