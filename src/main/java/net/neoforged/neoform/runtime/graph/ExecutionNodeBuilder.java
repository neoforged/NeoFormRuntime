package net.neoforged.neoform.runtime.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ExecutionNodeBuilder {
    private final String id;
    private final ExecutionGraph graph;
    private final Map<String, NodeInput> nodeInputs = new HashMap<>();
    private final Map<String, NodeOutput> nodeOutputs = new HashMap<>();
    private ExecutionNodeAction action;

    public ExecutionNodeBuilder(ExecutionGraph graph, String id) {
        this.graph = graph;
        this.id = Objects.requireNonNull(id, "id");
    }

    public String id() {
        return this.id;
    }

    public boolean hasInput(String inputId) {
        return nodeInputs.containsKey(inputId);
    }

    public NodeInput input(String inputId, NodeInput input) {
        if (nodeInputs.containsKey(inputId)) {
            throw new IllegalArgumentException("Duplicate input " + inputId + " on node " + id);
        }

        nodeInputs.put(inputId, Objects.requireNonNull(input, "input"));
        return input;
    }

    public NodeInput inputFromNodeOutput(String inputId, String nodeId, String outputId) {
        var node = graph.getNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node " + nodeId + " not found");
        }
        var output = node.getRequiredOutput(outputId);
        return input(inputId, new NodeInput.NodeInputForOutput(output));
    }

    public boolean hasOutput(String outputId) {
        return nodeOutputs.containsKey(outputId);
    }

    public NodeOutput output(String outputId, NodeOutputType type, String description) {
        // IDs of outputs may be used for naming files, and since we support case-insensitive file-systems
        // the ids should be unqiue regardless of case.
        if (nodeOutputs.keySet().stream().anyMatch(existingId -> existingId.equalsIgnoreCase(outputId))) {
            throw new IllegalArgumentException("Duplicate output " + outputId + " on node " + this.id);
        }

        var value = new NodeOutput(outputId, type, description);
        nodeOutputs.put(outputId, value);
        return value;
    }

    public void action(ExecutionNodeAction work) {
        this.action = work;
    }

    public void clearInputs() {
        nodeInputs.clear();
    }

    public ExecutionNode build() {
        if (action == null) {
            throw new IllegalStateException("No action registered for node " + id);
        }

        var node = new ExecutionNode(id, nodeInputs, nodeOutputs, action);
        graph.add(node);

        return node;
    }
}
