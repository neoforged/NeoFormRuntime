package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionGraph {
    private final Map<String, ExecutionNode> nodes = new LinkedHashMap<>();
    private final Map<String, ResolvedNodeOutput> nodeOutputs = new HashMap<>();

    public ExecutionNodeBuilder nodeBuilder(String id) {
        return new ExecutionNodeBuilder(this, id);
    }

    public void add(ExecutionNode node) {
        if (nodes.containsKey(node.toString())) {
            throw new IllegalArgumentException("Duplicate node id: " + node.id());
        }
        // Ensure the node id + output id are also unique
        for (var outputId : node.outputs().keySet()) {
            var globalOutputId = getGlobalNodeOutputId(node, outputId);
            var existingOutput = nodeOutputs.get(globalOutputId);
            if (existingOutput != null) {
                throw new IllegalArgumentException("Output id: " + outputId + " clashes with " + existingOutput);
            }
        }
        nodes.put(node.id(), node);
        for (var entry : node.outputs().entrySet()) {
            nodeOutputs.put(getGlobalNodeOutputId(node, entry.getKey()), new ResolvedNodeOutput(node, entry.getValue()));
        }
    }

    public ResolvedNodeOutput getRequiredOutput(String nodeId, String outputId) {
        var node = getNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }
        var output = node.outputs().get(outputId);
        if (output == null) {
            throw new IllegalArgumentException("Output " + outputId + " not found on node " + nodeId);
        }
        return new ResolvedNodeOutput(node, output);
    }

    @Nullable
    public ResolvedNodeOutput getOutput(String globalOutputId) {
        return nodeOutputs.get(globalOutputId);
    }

    private static String getGlobalNodeOutputId(ExecutionNode node, String outputId) {
        return node.id() + StringUtils.capitalize(outputId);
    }

    @Nullable
    public ExecutionNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }
}

