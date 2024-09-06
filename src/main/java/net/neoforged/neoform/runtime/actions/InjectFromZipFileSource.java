package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cli.FileHashService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
     * Optional regex used to filter inclusion file names.
     */
    @Nullable
    private final Pattern filePattern;

    public InjectFromZipFileSource(ZipFile zf, String sourcePath) {
        this(zf, sourcePath, null);
    }

    public InjectFromZipFileSource(ZipFile zf, String sourcePath, @Nullable Pattern filePattern) {
        this.zf = zf;
        this.sourcePath = sanitizeSourcePath(sourcePath);
        this.filePattern = filePattern;
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
            if ((sourcePath.isEmpty() || entry.getName().startsWith(sourcePath)) && (filePattern == null || filePattern.matcher(entry.getName()).matches())) {
                digestStream.write(entry.getName().getBytes());
                try (var in = zf.getInputStream(entry)) {
                    in.transferTo(digestStream);
                }
            }
        }

        return new CacheKey.AnnotatedValue(
                HexFormat.of().formatHex(digest.digest()),
                sourcePath + " from " + zf.getName() + (filePattern == null ? "" : " matching " + filePattern)
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
            if ((sourcePath.isEmpty() || entry.getName().startsWith(sourcePath)) && (filePattern == null || filePattern.matcher(entry.getName()).matches())) {
                try (var in = zf.getInputStream(entry)) {
                    // Relocate the entry
                    var copiedEntry = new ZipEntry(entry.getName().substring(sourcePath.length()));
                    if (copiedEntry.getName().isEmpty()) {
                        continue;
                    }
                    copiedEntry.setMethod(entry.getMethod());

                    out.putNextEntry(copiedEntry);
                    in.transferTo(out);
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
}
