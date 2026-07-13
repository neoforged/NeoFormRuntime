package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.engine.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheKeyBuilderTest {
    @Test
    void dataSourceCacheKeyIsStableForSameArchiveContents(@TempDir Path tempDir) throws IOException {
        var archive = tempDir.resolve("data.zip");
        writeZip(archive, Map.of("data.txt", "contents"));

        try (var zip = new ZipFile(archive.toFile())) {
            var keyA = cacheKeyForDataSource("patches", new DataSource(zip, "patches/"));
            var keyB = cacheKeyForDataSource("patches", new DataSource(zip, "patches/"));

            assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceCacheKeyIgnoresArchivePathWhenContentsMatch(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of("patches/data.patch", "patch"));
        writeZip(archiveB, Map.of("patches/data.patch", "patch"));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource("patches", new DataSource(zipA, "patches/"));
            var keyB = cacheKeyForDataSource("patches", new DataSource(zipB, "patches/"));

            assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceChangesCacheKeyWhenArchiveContentsChange(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of("data.txt", "a"));
        writeZip(archiveB, Map.of("data.txt", "b"));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource("patch", new DataSource(zipA, "data.txt"));
            var keyB = cacheKeyForDataSource("patch", new DataSource(zipB, "data.txt"));

            assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourcesCacheKeyChangesWhenAnyArchiveContentsChange(@TempDir Path tempDir) throws IOException {
        var patchesArchive = tempDir.resolve("patches.zip");
        var atsArchiveA = tempDir.resolve("ats-a.zip");
        var atsArchiveB = tempDir.resolve("ats-b.zip");
        writeZip(patchesArchive, Map.of("patches/data.txt", "patch"));
        writeZip(atsArchiveA, Map.of("ats/data.cfg", "at-a"));
        writeZip(atsArchiveB, Map.of("ats/data.cfg", "at-b"));

        try (var patchesZip = new ZipFile(patchesArchive.toFile());
             var atsZipA = new ZipFile(atsArchiveA.toFile());
             var atsZipB = new ZipFile(atsArchiveB.toFile())) {
            var keyA = cacheKeyForDataSources(Map.of(
                    "patches", new DataSource(patchesZip, "patches/"),
                    "ats", new DataSource(atsZipA, "ats/")
            ));
            var keyB = cacheKeyForDataSources(Map.of(
                    "patches", new DataSource(patchesZip, "patches/"),
                    "ats", new DataSource(atsZipB, "ats/")
            ));

            assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
        }
    }

    @Test
    void addDataSourcesRejectsDuplicateDataSourceIds(@TempDir Path tempDir) throws IOException {
        var archive = tempDir.resolve("data.zip");
        writeZip(archive, Map.of("data.txt", "data"));

        try (var zip = new ZipFile(archive.toFile())) {
            var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of(
                    "patches", new DataSource(zip, "patches/")
            ));

            assertThatThrownBy(() -> builder.addDataSources("data", List.of("patches", "patches")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void addDataSourceRejectsUnknownDataSource() {
        var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of());

        assertThatThrownBy(() -> builder.addDataSource("data[missing]", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    private static CacheKey cacheKeyForDataSource(String dataSourceId, DataSource dataSource) {
        var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of(dataSourceId, dataSource));
        builder.addDataSource("data[" + dataSourceId + "]", dataSourceId);
        return builder.build();
    }

    private static CacheKey cacheKeyForDataSources(Map<String, DataSource> dataSources) {
        var builder = new CacheKeyBuilder("test", new FileHashService(), dataSources);
        builder.addDataSources("data", dataSources.keySet());
        return builder.build();
    }

    private static void writeZip(Path path, Map<String, String> entries) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
