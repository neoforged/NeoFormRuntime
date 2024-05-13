package net.neoforged.neoform.runtime.engine;

import java.util.zip.ZipFile;

public record DataSource(ZipFile archive, String folder) {
}
