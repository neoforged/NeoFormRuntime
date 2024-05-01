package net.neoforged.neoforminabox.downloads;

import org.jetbrains.annotations.Nullable;

import java.net.URI;

public interface DownloadSpec {
    /**
     * The URI to download.
     */
    URI uri();

    /**
     * Expected size or -1 if unknown.
     */
    default int size() {
        return -1;
    }

    /**
     * Expected checksum, null if no check is to be performed.
     */
    @Nullable
    default String checksum() {
        return null;
    }

    /**
     * Checksum algorithm as per <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html">Java Security Standard Algorithm Names</a>.
     */
    @Nullable
    default String checksumAlgorithm() {
        return null;
    }

    static DownloadSpec of(URI uri) {
        return new SimpleDownloadSpec(uri);
    }
}

