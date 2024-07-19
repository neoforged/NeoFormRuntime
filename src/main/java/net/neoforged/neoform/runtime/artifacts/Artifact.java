package net.neoforged.neoform.runtime.artifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public record Artifact(Path path, long lastModified, long size) {
    public static Artifact ofPath(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new Artifact(path, attributes.lastModifiedTime().toMillis(), attributes.size());
    }
}
