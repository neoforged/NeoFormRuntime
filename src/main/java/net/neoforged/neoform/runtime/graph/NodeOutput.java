package net.neoforged.neoform.runtime.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NodeOutput {
    private final String id;
    private final NodeOutputType type;
    private final String description;
    private ExecutionNode node;
    private Path resultPath;
    // Allows caching an alternate in-memory representations of the result
    private final Map<ResultRepresentation<?>, Object> resultRepresentations = new HashMap<>();

    public NodeOutput(String id, NodeOutputType type, String description) {
        this.id = id;
        this.type = type;
        this.description = description;
    }

    public ExecutionNode getNode() {
        return Objects.requireNonNull(node, "node");
    }

    void setNode(ExecutionNode node) {
        this.node = node;
    }

    public synchronized Path getResultPath() {
        if (node.getState() != NodeState.COMPLETED) {
            throw new IllegalStateException("Trying to access " + this + " while node is in state " + node.getState());
        }
        return resultPath;
    }

    synchronized void setResultPath(Path result) {
        this.resultPath = result;
    }

    public String id() {
        return id;
    }

    public NodeOutputType type() {
        return type;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "output '" + id + "' of node '" + node.id() + "'";
    }

    public NodeInput asInput() {
        return new NodeInput.NodeInputForOutput(this);
    }

    public synchronized <T> T getResultRepresentation(ResultRepresentation<T> representation) throws IOException {
        var cachedResult = resultRepresentations.get(representation);

        if (cachedResult == null) {
            cachedResult = representation.loader().load(getResultPath());
            resultRepresentations.put(representation, cachedResult);
        }

        return representation.resultClass().cast(cachedResult);
    }
}
