package net.neoforged.neoforminabox.artifacts;

import java.nio.file.Path;

public record Artifact(Path path, long lastModified, long size) {
}
