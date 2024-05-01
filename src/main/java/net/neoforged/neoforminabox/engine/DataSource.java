package net.neoforged.neoforminabox.engine;

import java.util.zip.ZipFile;

public record DataSource(ZipFile archive, String folder) {
}
