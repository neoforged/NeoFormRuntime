package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.utils.AnsiColor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ExecutionNode {
    private final String id;
    private final Map<String, NodeInput> inputs;
    private final Map<String, NodeOutput> outputs;
    private ExecutionNodeAction action;
    private final Set<ExecutionNode> predecessors;
    private Long started;
    private long elapsedMs;
    private NodeState state = NodeState.NOT_STARTED;

    public ExecutionNode(String id, Map<String, NodeInput> inputs, Map<String, NodeOutput> outputs, ExecutionNodeAction action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        inputs = Map.copyOf(inputs);
        for (var entry : inputs.entrySet()) {
            entry.getValue().setNode(this);
            entry.getValue().setId(entry.getKey());
        }
        outputs = Map.copyOf(outputs);
        for (var output : outputs.values()) {
            output.setNode(this);
        }
        this.id = id;
        this.inputs = inputs;
        this.outputs = outputs;
        this.action = action;

        // Our predecessor nodes are nodes that our inputs depend on
        Set<ExecutionNode> predecessors = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var value : inputs.values()) {
            predecessors.addAll(value.getNodeDependencies());
        }
        this.predecessors = Collections.unmodifiableSet(predecessors);
    }

    public void start() {
        if (state != NodeState.NOT_STARTED) {
            throw new IllegalStateException("Node " + this + " already started.");
        }
        started = System.currentTimeMillis();
        state = NodeState.STARTED;
        var underlinedId = AnsiColor.BLACK_UNDERLINED + id() + AnsiColor.RESET;
        System.out.println(AnsiColor.BLACK_BOLD + "*** Started working on " + underlinedId + AnsiColor.RESET);
    }

    public void fail() {
        if (started != null) {
            elapsedMs = System.currentTimeMillis() - started;
        }
        state = NodeState.FAILED;
    }

    public void complete(Map<String, Path> outputs, boolean fromCache) {
        if (state != NodeState.STARTED) {
            throw new IllegalStateException("Node " + this + " not started yet.");
        }

        for (var entry : outputs.entrySet()) {
            var output = this.outputs.get(entry.getKey());
            if (output == null) {
                throw new IllegalArgumentException("Trying to set output " + entry.getKey() + " which does not exist on " + this);
            }
            output.setResultPath(entry.getValue());
        }

        // Validate that all outputs have received values
        for (var outputId : this.outputs.keySet()) {
            if (!outputs.containsKey(outputId)) {
                throw new IllegalArgumentException("No value for output " + outputId + " provided for node " + this);
            }
        }

        state = NodeState.COMPLETED;
        elapsedMs = System.currentTimeMillis() - started;
        var elapsedStr = String.format(Locale.ROOT, "%.02f", elapsedMs / 1000.0) + "s";
        var underlinedId = AnsiColor.BLACK_UNDERLINED + id() + AnsiColor.RESET;
        if (fromCache) {
            System.out.println(" " + AnsiColor.GREEN_BOLD_BRIGHT + "♻" + AnsiColor.RESET + " Used cache of " + underlinedId + " in " + elapsedStr);
        } else {
            System.out.println(" " + AnsiColor.GREEN_BOLD_BRIGHT + "✓" + AnsiColor.RESET + " Completed " + underlinedId + " in " + elapsedStr);
        }
    }

    public NodeState getState() {
        return state;
    }

    public String id() {
        return id;
    }

    public NodeInput getRequiredInput(String id) {
        var result = inputs.get(id);
        if (result == null) {
            throw new IllegalArgumentException("Input '" + id + "' does not exist on node '" + this + "'");
        }
        return result;
    }

    public Map<String, NodeInput> inputs() {
        return inputs;
    }

    public boolean hasOutput(String id) {
        return outputs.containsKey(id);
    }

    public NodeOutput getRequiredOutput(String id) {
        var result = outputs.get(id);
        if (result == null) {
            throw new IllegalArgumentException("Output " + id + " does not exist on node " + this);
        }
        return result;
    }

    public Map<String, NodeOutput> outputs() {
        return outputs;
    }

    public ExecutionNodeAction action() {
        return action;
    }

    @Override
    public String toString() {
        return id;
    }

    public Set<ExecutionNode> getPredecessors() {
        return predecessors;
    }

    public void setAction(ExecutionNodeAction action) {
        this.action = Objects.requireNonNull(action, "action");
    }
}

