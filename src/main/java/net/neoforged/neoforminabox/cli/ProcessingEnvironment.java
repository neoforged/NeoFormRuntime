package net.neoforged.neoforminabox.cli;

import java.nio.file.Path;

public interface ProcessingEnvironment {
    Path getWorkspace();

    default Path getRequiredInputPath(String id) {
        return getRequiredInput(id, Path.class);
    }

    <T> T getRequiredInput(String id, Class<T> resultClass);

    /**
     * Also automatically calls {@link #setOutput(String, Object)} with the generated path.
     */
    Path getOutputPath(String id);

    void setOutput(String id, Object path);
}
