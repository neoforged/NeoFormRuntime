package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKey;
import net.neoforged.neoform.runtime.cli.FileHashService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.zip.ZipOutputStream;

/**
 * Defines a source for injecting content into a zip file using the {@link InjectZipContentAction} task.
 *
 * @see InjectFromDirectorySource
 * @see InjectFromZipFileSource
 */
public interface InjectSource {
    CacheKey.AnnotatedValue getCacheKey(FileHashService fileHashService) throws IOException;

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

