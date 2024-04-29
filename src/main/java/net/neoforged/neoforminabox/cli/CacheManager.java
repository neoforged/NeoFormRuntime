package net.neoforged.neoforminabox.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CacheManager implements AutoCloseable {
    private final Path cacheDir;

    public CacheManager(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    @Override
    public void close() throws Exception {
    }
}
