package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.manifests.MinecraftVersionManifest;

import java.nio.file.Path;

public enum NodeOutputType {
    JAR(Path.class),
    TXT(Path.class),
    ZIP(Path.class),
    JSON(Path.class),
    TSRG(Path.class),
    VERSION_MANIFEST(MinecraftVersionManifest.class);

    private final Class<?> resultClass;

    NodeOutputType(Class<?> resultClass) {
        this.resultClass = resultClass;
    }

    public Class<?> getResultClass() {
        return resultClass;
    }

    public boolean isValidResult(Object result) {
        return resultClass.isInstance(result);
    }
}
