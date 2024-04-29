package net.neoforged.neoforminabox.cli;

import java.nio.file.Path;

public record Artifact(Path path, long lastModified, long size) {
}
