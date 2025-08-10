package net.neoforged.neoform.runtime.graph.transforms;

import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeOutput;

public final class ReplaceNodeOutput extends GraphTransform {
    private final String nodeId;
    private final String outputId;
    private final String newNodeId;
    private final NodeFactory nodeFactory;

    @FunctionalInterface
    public interface NodeFactory {
        NodeOutput make(ExecutionNodeBuilder builder, NodeOutput previousNodeOutput);
    }

    public ReplaceNodeOutput(String nodeId,
                             String outputId,
                             String newNodeId,
                             NodeFactory nodeFactory) {
        this.nodeId = nodeId;
        this.outputId = outputId;
        this.newNodeId = newNodeId;
        this.nodeFactory = nodeFactory;
    }

    public String nodeId() {
        return nodeId;
    }

    public String outputId() {
        return outputId;
    }

    @Override
    public void apply(NeoFormEngine engine, ExecutionGraph graph) {
        var originalOutput = graph.getRequiredOutput(nodeId, outputId);

        // Add the additional node
        var builder = graph.nodeBuilder(newNodeId);
        var newOutput = this.nodeFactory.make(builder, originalOutput);
        var newNode = builder.build();

        // Find all uses of the old output and replace them with our new output
        replaceOutput(graph, originalOutput, newOutput, newNode);
    }

    /**
     * General output -> output replacement.
     *
     * @param toIgnore node for which the {@code originalOutput} should not be replaced by the {@code newOutput},
     *                 because it is part of the replacement pipeline
     */
    public static void replaceOutput(ExecutionGraph graph, NodeOutput originalOutput, NodeOutput newOutput, ExecutionNode toIgnore) {
        for (var node : graph.getNodes()) {
            if (node != toIgnore) {
                for (var nodeInput : node.inputs().values()) {
                    nodeInput.replaceReferences(originalOutput, newOutput);
                }
            }
        }

        for (var entry : graph.getResults().entrySet()) {
            if (entry.getValue() == originalOutput) {
                entry.setValue(newOutput);
            }
        }
    }
}
