package net.neoforged.neoform.runtime.actions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * This content filter will strip signature related attributes from MANIFEST.MF entries.
 */
public class StripManifestDigestContentFilter implements InjectFromZipFileSource.ContentFilter {
    // Theoretically, we'd need to check all digests that the VM supports, but we just go for the most common.
    // https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html
    private static final Set<Attributes.Name> SIGNATURE_ATTRIBUTES = Set.of(
            new Attributes.Name("Magic"),
            new Attributes.Name("SHA-256-Digest"),
            new Attributes.Name("SHA1-Digest")
    );

    public static final StripManifestDigestContentFilter INSTANCE = new StripManifestDigestContentFilter();

    private StripManifestDigestContentFilter() {
    }

    @Override
    public void copy(ZipEntry entry, InputStream in, OutputStream out) throws IOException {
        if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
            in.transferTo(out);
        } else {
            var manifest = new Manifest(in);

            var it = manifest.getEntries().values().iterator();
            while (it.hasNext()) {
                // Remove all signing related attributes
                var entryAttrs = it.next();
                entryAttrs.keySet().removeIf(SIGNATURE_ATTRIBUTES::contains);
                // Remove entries that no longer have attributes
                if (entryAttrs.isEmpty()) {
                    it.remove();
                }
            }

            manifest.write(out);
        }
    }
}
