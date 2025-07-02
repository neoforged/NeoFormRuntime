package net.neoforged.neoform.runtime.graph.transforms;

import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeOutput;

import java.util.List;

public final class ReplaceNodeOutput extends GraphTransform {
    private final String nodeId;
    private final String outputId;
    private final GeneralizedNodeFactory nodeFactory;

    @FunctionalInterface
    public interface NodeFactory {
        NodeOutput make(ExecutionNodeBuilder builder, NodeOutput previousNodeOutput);
    }

    // TODO: cursed
    public record ReplacementResult(NodeOutput newOutput, List<ExecutionNode> nodesToIgnore) {}

    @FunctionalInterface
    public interface GeneralizedNodeFactory {
        ReplacementResult make(NeoFormEngine engine, NodeOutput previousNodeOutput);
    }

    public ReplaceNodeOutput(String nodeId,
                             String outputId,
                             String newNodeId,
                             NodeFactory nodeFactory) {
        this.nodeId = nodeId;
        this.outputId = outputId;
        this.nodeFactory = (engine, previousNodeOutput) -> {
            var builder = engine.getGraph().nodeBuilder(newNodeId);
            var newOutput = nodeFactory.make(builder, previousNodeOutput);
            var newNode = builder.build();
            return new ReplacementResult(newOutput, List.of(newNode));
        };
    }

    public ReplaceNodeOutput(String nodeId,
                             String outputId,
                             GeneralizedNodeFactory nodeFactory) {
        this.nodeId = nodeId;
        this.outputId = outputId;
        this.nodeFactory = nodeFactory;
    }

    public String nodeId() {
        return nodeId;
    }

    @Override
    public void apply(NeoFormEngine engine, ExecutionGraph graph) {
        var originalOutput = graph.getRequiredOutput(nodeId, outputId);

        var replacementResult = this.nodeFactory.make(engine, originalOutput);

        // Find all uses of the old output and replace them with our new output
        for (var node : graph.getNodes()) {
            if (!replacementResult.nodesToIgnore.contains(node)) {
                for (var nodeInput : node.inputs().values()) {
                    nodeInput.replaceReferences(originalOutput, replacementResult.newOutput);
                }
            }
        }

        for (var entry : graph.getResults().entrySet()) {
            if (entry.getValue() == originalOutput) {
                entry.setValue(replacementResult.newOutput);
            }
        }
    }
}
