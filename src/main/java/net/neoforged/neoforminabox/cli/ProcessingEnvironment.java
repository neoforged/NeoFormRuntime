package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.graph.ResultRepresentation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public interface ProcessingEnvironment {
    Path getWorkspace();

    default Path getRequiredInputPath(String id) {
        try {
            return getRequiredInput(id, ResultRepresentation.PATH);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // Getting a path should not fail
        }
    }

    <T> T getRequiredInput(String id, ResultRepresentation<T> representation) throws IOException;

    /**
     * Also automatically calls {@link #setOutput(String, Path)} with the generated path.
     */
    Path getOutputPath(String id);

    void setOutput(String id, Path resultPath);
}
