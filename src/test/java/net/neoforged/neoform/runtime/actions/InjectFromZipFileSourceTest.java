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
        writeZip(zipA, entries, false);
        writeZip(zipB, entries, true);

        assertThat(cacheKey(zipA).value()).isEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyChangesWhenIncludedContentChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        writeZip(zipA, Map.of("net/neoforged/test/A.java", "class A {}"), false);
        writeZip(zipB, Map.of("net/neoforged/test/A.java", "class Changed {}"), false);

        assertThat(cacheKey(zipA).value()).isNotEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyChangesWhenIncludedEntryNameChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        writeZip(zipA, Map.of("net/neoforged/test/A.java", "class Example {}"), false);
        writeZip(zipB, Map.of("net/neoforged/test/B.java", "class Example {}"), false);

        assertThat(cacheKey(zipA).value()).isNotEqualTo(cacheKey(zipB).value());
    }

    @Test
    void cacheKeyDoesNotChangeWhenExcludedContentChanges(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        writeZip(zipA, Map.of(
                "net/neoforged/test/A.java", "class A {}",
                "net/neoforged/test/A.txt", "a"
        ), false);
        writeZip(zipB, Map.of(
                "net/neoforged/test/A.java", "class A {}",
                "net/neoforged/test/A.txt", "b"
        ), false);

        var includeJava = Pattern.compile(".*\\.java");

        assertThat(cacheKey(zipA, "/", includeJava, null).value())
                .isEqualTo(cacheKey(zipB, "/", includeJava, null).value());
    }

    @Test
    void cacheKeyUsesContentFilterOutput(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        writeZip(zipA, Map.of("META-INF/MANIFEST.MF", "Digest: a"), false);
        writeZip(zipB, Map.of("META-INF/MANIFEST.MF", "Digest: b"), false);

        InjectFromZipFileSource.ContentFilter normalizeManifest = (entry, in, out) -> out.write("normalized".getBytes(StandardCharsets.UTF_8));

        assertThat(cacheKey(zipA, "/", null, normalizeManifest).value())
                .isEqualTo(cacheKey(zipB, "/", null, normalizeManifest).value());
    }

    @Test
    void cacheKeyDoesNotChangeWhenEntriesOutsideSourcePathChange(@TempDir Path tempDir) throws Exception {
        var zipA = tempDir.resolve("a.zip");
        var zipB = tempDir.resolve("b.zip");
        writeZip(zipA, Map.of(
                "source/A.java", "class A {}",
                "other/A.java", "class OtherA {}"
        ), false);
        writeZip(zipB, Map.of(
                "source/A.java", "class A {}",
                "other/A.java", "class OtherB {}"
        ), false);

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

    private static void writeZip(Path path, Map<String, String> entries, boolean reverseEntries) throws IOException {
        var zipEntries = new ArrayList<>(entries.entrySet());
        if (reverseEntries) {
            Collections.reverse(zipEntries);
        }

        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : zipEntries) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
