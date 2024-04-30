package net.neoforged.neoforminabox.graph;

public record ResolvedNodeOutput(ExecutionNode node, NodeOutput output) {
    public NodeInput asInput() {
        return new NodeInput.NodeInputForOutput(output);
    }
}
