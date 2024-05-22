package net.neoforged.neoform.runtime.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashingUtilTest {
    @TempDir
    Path tempDir;

    @Test
    void testHashEmptyFile() throws IOException {
        var emptyFile = tempDir.resolve("testfile");
        Files.createFile(emptyFile);
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", HashingUtil.hashFile(emptyFile, "SHA1"));
    }

    @Test
    void testHashFile() throws IOException {
        var emptyFile = tempDir.resolve("testfile");
        Files.writeString(emptyFile, "test");
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", HashingUtil.hashFile(emptyFile, "SHA1"));
    }
}