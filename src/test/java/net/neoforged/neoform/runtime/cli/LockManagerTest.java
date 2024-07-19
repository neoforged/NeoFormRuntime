package net.neoforged.neoform.runtime.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockManagerTest {

    private static final int MIN_AGE_TO_DELETE = 24;

    @TempDir
    Path tempDir;

    @Test
    void testMaintenance() throws Exception {
        touchFile("subdir/_totallyalock.lock", MIN_AGE_TO_DELETE);
        touchDir("_totallynota.lock");
        touchFile("_not_old_enough.lock", MIN_AGE_TO_DELETE - 1);
        touchFile("_should_be_deleted.lock", MIN_AGE_TO_DELETE);
        touchFile("doesntstartwithunderscore.lock", MIN_AGE_TO_DELETE);
        touchFile("_doesnotendwithlock", MIN_AGE_TO_DELETE);

        var lockManager = new LockManager(tempDir);
        lockManager.performMaintenance();

        // This asserts what was NOT deleted
        assertThat(listRecursively()).containsOnly(
                "doesntstartwithunderscore.lock",
                "subdir/",
                "subdir/_totallyalock.lock",
                "_doesnotendwithlock",
                "_not_old_enough.lock",
                "_totallynota.lock/"
        );
    }

    private List<String> listRecursively() throws IOException {
        try (var stream = Files.walk(tempDir)) {
            return stream.map(p -> {
                        var result = tempDir.relativize(p).toString().replace('\\', '/');
                        if (Files.isDirectory(p)) {
                            result += "/";
                        }
                        return result;
                    })
                    .filter(p -> !"/".equals(p)).toList();
        }
    }

    private void touchDir(String relativePath) throws IOException {
        var path = tempDir.resolve(relativePath);
        Files.createDirectories(path);
    }

    private void touchFile(String relativePath, long ageInHours) throws IOException {
        var path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(ageInHours, ChronoUnit.HOURS)));
    }
}
