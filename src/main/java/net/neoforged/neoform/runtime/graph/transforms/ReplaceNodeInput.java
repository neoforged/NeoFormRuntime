package net.neoforged.neoform.runtime.graph.transforms;

import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeInput;
import net.neoforged.neoform.runtime.graph.NodeOutput;

public final class ReplaceNodeInput extends GraphTransform {
    private final String nodeId;
    private final String inputId;
    private final String newNodeId;
    private final NodeFactory nodeFactory;

    @FunctionalInterface
    public interface NodeFactory {
        NodeOutput make(ExecutionNodeBuilder builder, NodeInput previousNodeInput);
    }

    public ReplaceNodeInput(String nodeId,
                            String inputId,
                            String newNodeId,
                            NodeFactory nodeFactory) {
        this.nodeId = nodeId;
        this.inputId = inputId;
        this.newNodeId = newNodeId;
        this.nodeFactory = nodeFactory;
    }

    public String nodeId() {
        return nodeId;
    }

    public String inputId() {
        return inputId;
    }

    @Override
    public void apply(NeoFormEngine engine, ExecutionGraph graph) {
        var originalInput = graph.getRequiredInput(nodeId, inputId);
        var originalNode = originalInput.getNode();

        // Add the additional node
        var builder = graph.nodeBuilder(newNodeId);
        var newOutput = this.nodeFactory.make(builder, originalInput);
        builder.build();

        originalNode.setInput(inputId, newOutput.asInput());
    }
}
