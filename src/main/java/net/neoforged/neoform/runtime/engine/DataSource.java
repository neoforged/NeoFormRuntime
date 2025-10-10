package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.cli.FileHashService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipFile;

public final class DataSource {
    private final ZipFile archive;
    private final String folder;
    private final FileHashService fileHashService;

    public DataSource(ZipFile archive, String folder, FileHashService fileHashService) {
        this.archive = archive;
        this.folder = folder;
        this.fileHashService = fileHashService;
    }

    public ZipFile archive() {
        return archive;
    }

    public String folder() {
        return folder;
    }

    public CacheKey.AnnotatedValue cacheKey() {
        try {
            var archivePath = Path.of(archive.getName());
            var hash = fileHashService.getHashValue(archivePath);
            return new CacheKey.AnnotatedValue(hash, CacheKeyBuilder.prettifyPath(archivePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute hash for archive " + archive.getName(), e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (DataSource) obj;
        return this.archive == that.archive && Objects.equals(this.folder, that.folder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(archive, folder);
    }

    @Override
    public String toString() {
        return archive.getName() + "!" + folder;
    }
}
