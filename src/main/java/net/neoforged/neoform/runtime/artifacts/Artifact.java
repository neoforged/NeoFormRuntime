package net.neoforged.neoform.runtime.artifacts;

import java.nio.file.Path;

public record Artifact(Path path, long lastModified, long size) {
}
