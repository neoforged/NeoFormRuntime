package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cli.FileHashService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Inject content from another ZIP-file.
 *
 * @see InjectZipContentAction
 */
public class InjectFromZipFileSource implements InjectSource {
    private final ZipFile zf;
    /**
     * Folder within the ZIP we're copying from
     */
    private final String sourcePath;
    /**
     * Optional regex for filtering which entries from this source will be injected.
     * The relative path in the ZIP file will be matched against this regular expression.
     * If the pattern is null, all entries are included.
     */
    @Nullable
    private final Pattern includeFilterPattern;
    /**
     * This function can modify the content that is being copied.
     */
    private final ContentFilter contentFilter;

    public InjectFromZipFileSource(ZipFile zf, String sourcePath) {
        this(zf, sourcePath, null);
    }

    public InjectFromZipFileSource(ZipFile zf, String sourcePath, @Nullable Pattern includeFilterPattern) {
        this(zf, sourcePath, includeFilterPattern, null);
    }

    public InjectFromZipFileSource(ZipFile zf, String sourcePath, @Nullable Pattern includeFilterPattern, @Nullable ContentFilter contentFilter) {
        this.zf = zf;
        this.sourcePath = sanitizeSourcePath(sourcePath);
        this.includeFilterPattern = includeFilterPattern;
        this.contentFilter = Objects.requireNonNullElse(contentFilter, ContentFilter.NONE);
    }

    private static String sanitizeSourcePath(String sourcePath) {
        while (sourcePath.startsWith("/")) {
            sourcePath = sourcePath.substring(1);
        }
        if (!sourcePath.isEmpty() && !sourcePath.endsWith("/")) {
            sourcePath = sourcePath + "/";
        }
        return sourcePath;
    }

    @Override
    public CacheKey.AnnotatedValue getCacheKey(FileHashService fileHashService) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        var digestStream = new DigestOutputStream(OutputStream.nullOutputStream(), digest);

        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if ((sourcePath.isEmpty() || entry.getName().startsWith(sourcePath)) && matchesIncludeFilter(entry)) {
                digestStream.write(entry.getName().getBytes());
                try (var in = zf.getInputStream(entry)) {
                    contentFilter.copy(entry, in, digestStream);
                }
            }
        }

        return new CacheKey.AnnotatedValue(
                HexFormat.of().formatHex(digest.digest()),
                sourcePath + " from " + zf.getName() + (includeFilterPattern == null ? "" : " matching " + includeFilterPattern.pattern())
        );
    }

    @Override
    public byte @Nullable [] tryReadFile(String path) throws IOException {
        var entry = zf.getEntry(this.sourcePath + path);
        if (entry != null) {
            try (var in = zf.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
        return null;
    }

    @Override
    public void copyTo(ZipOutputStream out) throws IOException {
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if ((sourcePath.isEmpty() || entry.getName().startsWith(sourcePath)) && matchesIncludeFilter(entry)) {
                try (var in = zf.getInputStream(entry)) {
                    // Relocate the entry
                    var copiedEntry = new ZipEntry(entry.getName().substring(sourcePath.length()));
                    if (copiedEntry.getName().isEmpty()) {
                        continue;
                    }
                    copiedEntry.setMethod(entry.getMethod());
                    copiedEntry.setCrc(entry.getCrc());
                    copiedEntry.setSize(entry.getSize());
                    copiedEntry.setTime(entry.getTime());

                    out.putNextEntry(copiedEntry);
                    contentFilter.copy(entry, in, out);
                    out.closeEntry();
                } catch (ZipException e) {
                    if (!e.getMessage().startsWith("duplicate entry:")) {
                        throw e;
                    } else if (!entry.isDirectory()) {
                        // Warn on duplicate files, but ignore duplicate directories
                        System.err.println("Cannot inject duplicate file " + entry.getName());
                    }
                }
            }
        }
    }

    private boolean matchesIncludeFilter(ZipEntry entry) {
        return includeFilterPattern == null || includeFilterPattern.matcher(entry.getName()).matches();
    }

    @FunctionalInterface
    public interface ContentFilter {
        ContentFilter NONE = (entry, in, out) -> in.transferTo(out);

        void copy(ZipEntry entry, InputStream in, OutputStream out) throws IOException;
    }
}
