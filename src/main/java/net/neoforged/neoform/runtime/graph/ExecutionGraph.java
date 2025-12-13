package net.neoforged.neoform.runtime.graph;

import net.neoforged.neoform.runtime.utils.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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

    private static final Pattern DEBUG_OUTPUT_PATTERN = Pattern.compile("^node\\.([a-zA-Z0-9]+)\\.output\\.([a-zA-Z0-9]+)$");

    public NodeOutput getResult(String id) {
        var output = results.get(id);
        if (output != null) {
            return output;
        }
        var matcher = DEBUG_OUTPUT_PATTERN.matcher(id);
        if (matcher.matches()) {
            var step = matcher.group(1);
            var outputId = matcher.group(2);
            return getRequiredOutput(step, outputId);
        }
        throw new IllegalArgumentException("Unknown result: " + id + ". Available results: " + getAvailableResults());
    }

    public Set<String> getAvailableResults() {
        // Sort alphabetically for nicer printing
        return new TreeSet<>(results.keySet());
    }

    public Map<String, NodeOutput> getResults() {
        return results;
    }

    public NodeOutput getRequiredOutput(String nodeId, String outputId) {
        return getRequiredNode(nodeId).getRequiredOutput(outputId);
    }

    public NodeInput getRequiredInput(String nodeId, String inputId) {
        return getRequiredNode(nodeId).getRequiredInput(inputId);
    }

    @Nullable
    public NodeOutput getOutput(String globalOutputId) {
        return nodeOutputs.get(globalOutputId);
    }

    private static String getGlobalNodeOutputId(ExecutionNode node, String outputId) {
        return node.id() + StringUtil.capitalize(outputId);
    }

    public ExecutionNode getRequiredNode(String nodeId) {
        var node = getNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }
        return node;
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

            writer.println("%%{init: {\"flowchart\": {\"htmlLabels\": false}} }%%");
            writer.println("flowchart LR");

            for (var node : sortedNodes) {
                writer.println("  " + node.id() + "[[" + node.id() + "]]");

                for (var input : node.inputs().values()) {
                    for (var inputNode : input.getNodeDependencies()) {
                        writer.println("  " + inputNode.id() + "-->|" + input.getId() + "|" + node.id());
                    }
                }
            }

            for (var entry : results.entrySet()) {
                writer.println("  result-" + entry.getKey() + "(\"`**Result**\n" + entry.getKey() + "`\")");
                writer.println("  " + entry.getValue().getNode().id() + " --o " + "result-" + entry.getKey());
            }

        } finally {
            writer.flush();
        }

    }

    public Collection<ExecutionNode> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }
}

