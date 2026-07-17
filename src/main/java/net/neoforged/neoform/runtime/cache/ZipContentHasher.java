package net.neoforged.neoform.runtime.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ZipContentHasher {
    private final ZipFile zipFile;
    private final EntryContentFilter contentFilter;
    private final MessageDigest digest = newSha1Digest();
    private final Map<String, CacheKey.AnnotatedValue> entries = new LinkedHashMap<>();

    public ZipContentHasher(ZipFile zipFile) {
        this(zipFile, EntryContentFilter.NONE);
    }

    public ZipContentHasher(ZipFile zipFile, EntryContentFilter contentFilter) {
        this.zipFile = Objects.requireNonNull(zipFile);
        this.contentFilter = Objects.requireNonNull(contentFilter);
    }

    public void addEntry(ZipEntry entry) throws IOException {
        if (entries.containsKey(entry.getName())) {
            throw new IllegalArgumentException("Duplicate ZIP entry: " + entry.getName());
        }
        entries.put(entry.getName(), new CacheKey.AnnotatedValue(hashEntry(entry), null));
    }

    public void addFileOrDirectoryPath(String path) throws IOException {
        if (!path.isEmpty()) {
            var rootEntry = zipFile.getEntry(path);
            if (rootEntry != null && !rootEntry.isDirectory()) {
                addEntry(rootEntry);
                return;
            }
        }

        if (addFileEntriesUnderPath(path, entry -> true) == 0) {
            throw new IllegalArgumentException("ZIP path " + path + " does not select any file entries in " + zipFile.getName());
        }
    }

    public int addFileEntriesUnderPath(String path, Predicate<ZipEntry> entryFilter) throws IOException {
        int count = 0;
        var entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.isDirectory() && (path.isEmpty() || entry.getName().startsWith(path)) && entryFilter.test(entry)) {
                addEntry(entry);
                count++;
            }
        }
        return count;
    }

    public String getHash() {
        return new CacheKey("zipContent", entries).hashValue();
    }

    private String hashEntry(ZipEntry entry) throws IOException {
        digest.reset();
        try (var in = zipFile.getInputStream(entry);
             var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            contentFilter.copy(entry, in, out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha1Digest() {
        try {
            return MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface EntryContentFilter {
        EntryContentFilter NONE = (entry, in, out) -> in.transferTo(out);

        void copy(ZipEntry entry, InputStream in, OutputStream out) throws IOException;
    }
}
