package net.neoforged.neoforminabox.graph;

import java.util.Objects;

public final class NodeOutput {
    private final String id;
    private final NodeOutputType type;
    private final String description;
    private ExecutionNode node;
    private Object result;

    public NodeOutput(String id, NodeOutputType type, String description) {
        this.id = id;
        this.type = type;
        this.description = description;
    }

    public ExecutionNode getNode() {
        return Objects.requireNonNull(node, "node");
    }

    void setNode(ExecutionNode node) {
        this.node = node;
    }

    void setResult(Object result) {
        this.result = result;
    }

    public Object getResult() {
        if (node.getState() != NodeState.COMPLETED) {
            throw new IllegalStateException("Trying to access " + this + " while node is in state " + node.getState());
        }
        return result;
    }

    public String id() {
        return id;
    }

    public NodeOutputType type() {
        return type;
    }

    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (NodeOutput) obj;
        return Objects.equals(this.id, that.id) &&
               Objects.equals(this.type, that.type) &&
               Objects.equals(this.description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, description);
    }

    @Override
    public String toString() {
        return "output '" + id + "' of node '" + node.id() + "'";
    }
}
