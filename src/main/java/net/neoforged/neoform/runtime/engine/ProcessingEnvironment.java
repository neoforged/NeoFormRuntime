package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.graph.ResultRepresentation;
import net.neoforged.problems.ProblemReporter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public interface ProcessingEnvironment {
    ArtifactManager getArtifactManager();

    Path getWorkspace();

    /**
     * @return The Java executable for running external Java tools.
     */
    String getJavaExecutable();

    /**
     * Interpolates a string containing placeholders of the form {@code {variable}} that may refer to:
     *
     * <ul>
     *     <li>Declared inputs of the node.</li>
     *     <li>Declared outputs of the node.</li>
     *     <li>Data directories found in the configuration, which will be unpacked to the {@linkplain #getWorkspace() workspace}, and their
     *     path being used as the value of the variable.</li>
     * </ul>
     */
    String interpolateString(String text) throws IOException;

    /**
     * Extract data from the configuration archive, whose location is declared in the config under the given id.
     */
    Path extractData(String id) throws IOException;

    default Path getRequiredInputPath(String id) {
        try {
            return getRequiredInput(id, ResultRepresentation.PATH);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // Getting a path should not fail
        }
    }

    @Nullable
    default Path getInputPath(String id) {
        try {
            return getInput(id, ResultRepresentation.PATH);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // Getting a path should not fail
        }
    }

    <T> T getRequiredInput(String id, ResultRepresentation<T> representation) throws IOException;

    @Nullable
    <T> T getInput(String id, ResultRepresentation<T> representation) throws IOException;

    /**
     * Also automatically calls {@link #setOutput(String, Path)} with the generated path.
     */
    Path getOutputPath(String id);

    void setOutput(String id, Path resultPath);

    boolean isVerbose();

    /**
     * Get a reporter for reporting problems that occurred during execution of this action.
     */
    ProblemReporter getProblemReporter();

    /**
     * Format a path as a command-line argument, trying to keep it terse.
     */
    default String getPathArgument(Path path) {
        // Assume that our working directory is the work directory
        if (path.startsWith(getWorkspace())) {
            path = getWorkspace().relativize(path);
        }
        if (path.getParent() == null) {
            // Some tooling can't deal with paths that do not have directories
            return "./" + path;
        }
        return path.toString();
    }
}
