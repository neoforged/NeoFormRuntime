package net.neoforged.neoforminabox.tasks;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.zip.ZipOutputStream;

/**
 * Defines a source for injecting content into a zip file using the {@link InjectZipContentTask} task.
 *
 * @see InjectFromDirectorySource
 * @see InjectFromZipSource
 */
public interface InjectSource {
    /**
     * Tries to read a file from this source.
     *
     * @return null if the file doesn't exist.
     */
    byte @Nullable [] tryReadFile(String path) throws IOException;

    /**
     * Copy the contents of this source to the given zip output stream.
     * <p>
     * Files that have already been written to {@code out} should issue a warning, while directories
     * should simply be ignored.
     */
    void copyTo(ZipOutputStream out) throws IOException;
}

