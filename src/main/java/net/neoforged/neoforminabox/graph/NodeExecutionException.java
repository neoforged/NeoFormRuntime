package net.neoforged.neoforminabox.graph;

public class NodeExecutionException extends RuntimeException {
    private final ExecutionNode node;

    public NodeExecutionException(ExecutionNode node, Throwable cause) {
        super("Node action for " + node.id() + " failed", cause);
        this.node = node;
    }

    public ExecutionNode getNode() {
        return node;
    }
}
