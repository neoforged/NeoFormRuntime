package net.neoforged.neoforminabox.actions;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Inject content from another ZIP-file.
 *
 * @see InjectZipContentAction
 */
public class InjectFromZipSource implements InjectSource {
    private final ZipFile zf;
    /**
     * Folder within the ZIP we're copying from
     */
    private final String sourcePath;

    public InjectFromZipSource(ZipFile zf, String sourcePath) {
        this.zf = zf;
        while (sourcePath.startsWith("/")) {
            sourcePath = sourcePath.substring(1);
        }
        if (!sourcePath.isEmpty() && !sourcePath.endsWith("/")) {
            sourcePath = sourcePath + "/";
        }
        this.sourcePath = sourcePath;
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
            if (sourcePath.isEmpty() || entry.getName().startsWith(sourcePath)) {
                try (var in = zf.getInputStream(entry)) {
                    // Relocate the entry
                    var copiedEntry = new ZipEntry(entry.getName().substring(sourcePath.length()));
                    copiedEntry.setMethod(entry.getMethod());

                    out.putNextEntry(entry);
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
