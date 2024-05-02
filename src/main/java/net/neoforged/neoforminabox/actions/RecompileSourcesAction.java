package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.artifacts.ClasspathItem;
import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.engine.ProcessingEnvironment;
import net.neoforged.neoforminabox.graph.ResultRepresentation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public abstract class RecompileSourcesAction extends BuiltInAction implements ActionWithClasspath {
    private final List<ClasspathItem> classpathItems = new ArrayList<>();

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);

        ck.add("libraries", classpathItems.stream().map(ClasspathItem::toString).collect(Collectors.joining(", ")));
    }

    protected final List<Path> getLibraries(ProcessingEnvironment environment) throws IOException {
        var versionManifest = environment.getRequiredInput("versionManifest", ResultRepresentation.MINECRAFT_VERSION_MANIFEST);

        // Merge the original Minecraft classpath with the libs required by additional patches that we made
        var effectiveClasspathItems = new ArrayList<ClasspathItem>();
        for (var library : versionManifest.libraries()) {
            effectiveClasspathItems.add(ClasspathItem.of(library));
        }
        effectiveClasspathItems.addAll(classpathItems);

        var classpath = environment.getArtifactManager().resolveClasspath(effectiveClasspathItems);

        System.out.println(" Compile Classpath:");
        var userHome = Paths.get(System.getProperty("user.home"));
        for (var path : classpath) {
            if (path.startsWith(userHome)) {
                System.out.println("  ~/" + userHome.relativize(path).toString().replace('\\', '/'));
            } else {
                System.out.println(path);
            }
        }

        return classpath;
    }

    @Override
    public List<ClasspathItem> getClasspath() {
        return classpathItems;
    }
}
