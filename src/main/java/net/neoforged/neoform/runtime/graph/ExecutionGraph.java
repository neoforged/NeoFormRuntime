package net.neoforged.neoform.runtime.graph;

import net.neoforged.neoform.runtime.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionGraph {
    private final Map<String, ExecutionNode> nodes = new LinkedHashMap<>();
    private final Map<String, NodeOutput> nodeOutputs = new HashMap<>();
    private final Map<String, NodeOutput> results = new HashMap<>();

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
            nodeOutputs.put(getGlobalNodeOutputId(node, entry.getKey()), entry.getValue());
        }
    }

    public void setResult(String id, NodeOutput output) {
        results.put(id, output);
    }

    public NodeOutput getResult(String id) {
        return results.get(id);
    }

    public Map<String, NodeOutput> getResults() {
        return results;
    }

    public NodeOutput getRequiredOutput(String nodeId, String outputId) {
        var node = getNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }
        var output = node.outputs().get(outputId);
        if (output == null) {
            throw new IllegalArgumentException("Output " + outputId + " not found on node " + nodeId);
        }
        return output;
    }

    @Nullable
    public NodeOutput getOutput(String globalOutputId) {
        return nodeOutputs.get(globalOutputId);
    }

    private static String getGlobalNodeOutputId(ExecutionNode node, String outputId) {
        return node.id() + StringUtils.capitalize(outputId);
    }

    @Nullable
    public ExecutionNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public boolean hasOutput(String nodeId, String outputId) {
        var node = getNode(nodeId);
        return node != null && node.hasOutput(outputId);
    }

    public void dump(PrintWriter writer) {

        try {

            var sortedNodes = TopologicalSort.topologicalSort(this);

            for (var node : sortedNodes) {
                writer.println("*** NODE " + node.id());
                for (var predecessor : node.getPredecessors()) {
                    writer.println("  needs " + predecessor.id());
                }
            }

        } finally {
            writer.flush();
        }

    }

    public Collection<ExecutionNode> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }
}

