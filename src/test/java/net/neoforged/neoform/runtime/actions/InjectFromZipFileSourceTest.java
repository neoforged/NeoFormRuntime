package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cli.FileHashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class InjectFromZipFileSourceTest {
    @Test
    void cacheKeyDoesNotChangeWhenZipEntryOrderChanges(@TempDir Path tempDir) throws Exception {
        var entries = Map.of(
                "net/neoforged/test/A.java", "class A {}",
                "net/neoforged/test/B.java", "class B {}"
        );
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(entries).write(zipA);
        zipEntries(entries).reverseEntries().write(zipB);

        assertThat(cacheKey(zipA).value()).isEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyDoesNotChangeWhenZipEntryTimesChange(@TempDir Path tempDir) throws Exception {
        var entries = Map.of("net/neoforged/test/A.java", "class A {}");
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(entries).setTime(0).write(zipA);
        zipEntries(entries).setTime(1000).write(zipB);

        assertThat(cacheKey(zipA).value()).isEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyChangesWhenIncludedContentChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(Map.of("net/neoforged/test/A.java", "class A {}")).write(zipA);
        zipEntries(Map.of("net/neoforged/test/A.java", "class Changed {}")).write(zipB);

        assertThat(cacheKey(zipA).value()).isNotEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyChangesWhenIncludedEntryNameChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(Map.of("net/neoforged/test/A.java", "class Example {}")).write(zipA);
        zipEntries(Map.of("net/neoforged/test/B.java", "class Example {}")).write(zipB);

        assertThat(cacheKey(zipA).value()).isNotEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyDoesNotChangeWhenExcludedContentChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(Map.of(
                "net/neoforged/test/A.java", "class A {}",
                "net/neoforged/test/A.txt", "a"
        )).write(zipA);
        zipEntries(Map.of(
                "net/neoforged/test/A.java", "class A {}",
                "net/neoforged/test/A.txt", "b"
        )).write(zipB);

        var includeJava = Pattern.compile(".*\\.java");

        assertThat(cacheKey(zipA, "/", includeJava, null).value())
                .isEqualTo(cacheKey(zipB, "/", includeJava, null).value());
    }

    @Test
    void cacheKeyUsesContentFilterOutput(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(Map.of("META-INF/MANIFEST.MF", "Digest: a")).write(zipA);
        zipEntries(Map.of("META-INF/MANIFEST.MF", "Digest: b")).write(zipB);

        InjectFromZipFileSource.ContentFilter normalizeManifest = (entry, in, out) -> out.write("normalized".getBytes(StandardCharsets.UTF_8));

        assertThat(cacheKey(zipA, "/", null, normalizeManifest).value())
                .isEqualTo(cacheKey(zipB, "/", null, normalizeManifest).value());
    }

    @Test
    void cacheKeyDoesNotChangeWhenEntriesOutsideSourcePathChange(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        zipEntries(Map.of(
                "source/A.java", "class A {}",
                "other/A.java", "class OtherA {}"
        )).write(zipA);
        zipEntries(Map.of(
                "source/A.java", "class A {}",
                "other/A.java", "class OtherB {}"
        )).write(zipB);

        assertThat(cacheKey(zipA, "source/", null, null).value())
                .isEqualTo(cacheKey(zipB, "source/", null, null).value());
    }

    private static CacheKey.AnnotatedValue cacheKey(Path zip) throws IOException {
        return cacheKey(zip, "/", null, null);
    }

    private static CacheKey.AnnotatedValue cacheKey(Path zip,
                                                    String sourcePath,
                                                    Pattern includeFilterPattern,
                                                    InjectFromZipFileSource.ContentFilter contentFilter) throws IOException {
        try (var zipFile = new ZipFile(zip.toFile())) {
            return new InjectFromZipFileSource(zipFile, sourcePath, includeFilterPattern, contentFilter).getCacheKey(new FileHashService());
        }
    }

    private static ZipEntryBuilder zipEntries(Map<String, String> entries) {
        return new ZipEntryBuilder(entries);
    }

    private static final class ZipEntryBuilder {
        private final ArrayList<Map.Entry<String, String>> entries;
        private long time;
        private boolean reverseEntries;

        private ZipEntryBuilder(Map<String, String> entries) {
            this.entries = new ArrayList<>(entries.entrySet());
        }

        private ZipEntryBuilder reverseEntries() {
            reverseEntries = true;
            return this;
        }

        private ZipEntryBuilder setTime(long time) {
            this.time = time;
            return this;
        }

        private void write(Path path) throws IOException {
            var zipEntries = new ArrayList<>(entries);
            if (reverseEntries) {
                Collections.reverse(zipEntries);
            }

            try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
                for (var entry : zipEntries) {
                    var zipEntry = new ZipEntry(entry.getKey());
                    zipEntry.setTime(time);
                    output.putNextEntry(zipEntry);
                    output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
        }
    }
}
