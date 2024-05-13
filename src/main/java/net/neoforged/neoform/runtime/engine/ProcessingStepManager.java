package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cli.CacheManager;
import net.neoforged.neoform.runtime.utils.FilenameUtil;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class ProcessingStepManager {
    private static final DateTimeFormatter WORKSPACE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path workspacesDir;
    private final CacheManager cacheManager;
    private final ArtifactManager artifactManager;

    public ProcessingStepManager(Path workspacesDir, CacheManager cacheManager, ArtifactManager artifactManager) {
        this.workspacesDir = workspacesDir;
        this.cacheManager = cacheManager;
        this.artifactManager = artifactManager;
    }

    public Path createWorkspace(String stepName) throws IOException {
        Files.createDirectories(workspacesDir);
        // Set up a workspace directory
        var timestamp = WORKSPACE_NAME_FORMAT.format(LocalDateTime.now());
        var workspaceBasename = timestamp + "_" + FilenameUtil.sanitizeForFilename(stepName);
        var workspaceDir = workspacesDir.resolve(workspaceBasename);
        for (var i = 1; i <= 999; i++) {
            try {
                Files.createDirectory(workspaceDir);
                break;
            } catch (FileAlreadyExistsException e) {
                workspaceDir = workspaceDir.resolveSibling(workspaceBasename + "_" + String.format(Locale.ROOT, "%03d", i));
            }
        }
        if (!Files.isDirectory(workspaceDir)) {
            throw new IOException("Failed to create a suitable workspace directory " + workspaceDir);
        }
        return workspaceDir;
    }

}
