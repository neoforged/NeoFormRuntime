package net.neoforged.neoform.runtime.artifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;

public record Artifact(Path path, long lastModified, long size) {
    public static Artifact ofPath(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
        var attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
        return new Artifact(path, attrView.lastModifiedTime().toMillis(), attrView.size());
    }
}
