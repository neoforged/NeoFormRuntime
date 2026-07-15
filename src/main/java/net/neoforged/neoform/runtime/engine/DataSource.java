package net.neoforged.neoform.runtime.engine;

import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipFile;

public final class DataSource {
    private final String id;
    private final ZipFile archive;
    private final Path archivePath;
    private final String folder;

    public DataSource(String id, ZipFile archive, String folder) {
        this.id = id;
        this.archive = archive;
        this.archivePath = Path.of(archive.getName());
        this.folder = folder;
    }

    public String id() {
        return id;
    }

    public ZipFile archive() {
        return archive;
    }

    public Path archivePath() {
        return archivePath;
    }

    public String folder() {
        return folder;
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
        return archivePath + "!" + folder;
    }
}
