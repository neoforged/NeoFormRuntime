package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.engine.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        writeZip(archive, Map.of("patches/data.txt", "contents"));

        try (var zip = new ZipFile(archive.toFile())) {
            var keyA = cacheKeyForDataSource(new DataSource("patches", zip, "patches/"));
            var keyB = cacheKeyForDataSource(new DataSource("patches", zip, "patches/"));

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
            var keyA = cacheKeyForDataSource(new DataSource("patches", zipA, "patches/"));
            var keyB = cacheKeyForDataSource(new DataSource("patches", zipB, "patches/"));

            assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceCacheKeyIgnoresDataSourceIdWhenContentsMatch(@TempDir Path tempDir) throws IOException {
        var archive = tempDir.resolve("data.zip");
        writeZip(archive, Map.of("patches/data.patch", "patch"));

        try (var zip = new ZipFile(archive.toFile())) {
            var keyA = cacheKeyForDataSource("data", new DataSource("patches", zip, "patches/"));
            var keyB = cacheKeyForDataSource("data", new DataSource("renamed", zip, "patches/"));

            assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceCacheKeyIgnoresUnselectedArchiveContents(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of(
                "patches/data.patch", "patch",
                "unrelated.txt", "a"
        ));
        writeZip(archiveB, Map.of(
                "patches/data.patch", "patch",
                "unrelated.txt", "b"
        ));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource(new DataSource("patches", zipA, "patches/"));
            var keyB = cacheKeyForDataSource(new DataSource("patches", zipB, "patches/"));

            assertThat(keyA.hashValue()).isEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceFileCacheKeyIgnoresEntriesWithSamePrefix(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of("data.txt", "data"));
        writeZip(archiveB, Map.of(
                "data.txt", "data",
                "data.txt.extra", "extra"
        ));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource(new DataSource("data", zipA, "data.txt"));
            var keyB = cacheKeyForDataSource(new DataSource("data", zipB, "data.txt"));

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
            var keyA = cacheKeyForDataSource(new DataSource("patch", zipA, "data.txt"));
            var keyB = cacheKeyForDataSource(new DataSource("patch", zipB, "data.txt"));

            assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceCacheKeyChangesWhenSelectedEntryNameChanges(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of("patches/a.patch", "patch"));
        writeZip(archiveB, Map.of("patches/b.patch", "patch"));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource(new DataSource("patches", zipA, "patches/"));
            var keyB = cacheKeyForDataSource(new DataSource("patches", zipB, "patches/"));

            assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
        }
    }

    @Test
    void dataSourceCacheKeyChangesWhenSelectedEntryIsAdded(@TempDir Path tempDir) throws IOException {
        var archiveA = tempDir.resolve("a.zip");
        var archiveB = tempDir.resolve("b.zip");
        writeZip(archiveA, Map.of("patches/a.patch", "a"));
        writeZip(archiveB, Map.of(
                "patches/a.patch", "a",
                "patches/b.patch", "b"
        ));

        try (var zipA = new ZipFile(archiveA.toFile());
             var zipB = new ZipFile(archiveB.toFile())) {
            var keyA = cacheKeyForDataSource(new DataSource("patches", zipA, "patches/"));
            var keyB = cacheKeyForDataSource(new DataSource("patches", zipB, "patches/"));

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
            var keyA = cacheKeyForDataSources(
                    new DataSource("patches", patchesZip, "patches/"),
                    new DataSource("ats", atsZipA, "ats/")
            );
            var keyB = cacheKeyForDataSources(
                    new DataSource("patches", patchesZip, "patches/"),
                    new DataSource("ats", atsZipB, "ats/")
            );

            assertThat(keyA.hashValue()).isNotEqualTo(keyB.hashValue());
        }
    }

    @Test
    void addDataSourcesRejectsDuplicateDataSourceIds(@TempDir Path tempDir) throws IOException {
        var archive = tempDir.resolve("data.zip");
        writeZip(archive, Map.of("patches/data.txt", "data"));

        try (var zip = new ZipFile(archive.toFile())) {
            var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of(
                    "patches", new DataSource("patches", zip, "patches/")
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

    @Test
    void addDataSourceRejectsDataSourceWithNoFileEntries(@TempDir Path tempDir) throws IOException {
        var archive = tempDir.resolve("data.zip");
        writeZip(archive, Map.of("patches/", ""));

        try (var zip = new ZipFile(archive.toFile())) {
            var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of(
                    "patches", new DataSource("patches", zip, "patches/")
            ));

            assertThatThrownBy(() -> builder.addDataSource("data[patches]", "patches"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("patches/");
        }
    }

    private static CacheKey cacheKeyForDataSource(DataSource dataSource) {
        return cacheKeyForDataSource("data[" + dataSource.id() + "]", dataSource);
    }

    private static CacheKey cacheKeyForDataSource(String component, DataSource dataSource) {
        var builder = new CacheKeyBuilder("test", new FileHashService(), Map.of(dataSource.id(), dataSource));
        builder.addDataSource(component, dataSource.id());
        return builder.build();
    }

    private static CacheKey cacheKeyForDataSources(DataSource... dataSources) {
        var dataSourcesById = new LinkedHashMap<String, DataSource>();
        for (var dataSource : dataSources) {
            dataSourcesById.put(dataSource.id(), dataSource);
        }
        var builder = new CacheKeyBuilder("test", new FileHashService(), dataSourcesById);
        builder.addDataSources("data", dataSourcesById.keySet());
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
