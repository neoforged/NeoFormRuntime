package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.cache.CacheKeyBuilder;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

public abstract class NodeInput {
    private String id;
    private ExecutionNode node;

    public String getId() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }

    public ExecutionNode getNode() {
        return node;
    }

    void setNode(ExecutionNode node) {
        this.node = node;
    }

    /**
     * replace any reference to a given node output with another node output if they occur within this input.
     */
    public abstract void replaceReferences(NodeOutput oldOutput, NodeOutput newOutput);

    public abstract Collection<ExecutionNode> getNodeDependencies();

    public abstract void collectCacheKeyComponent(CacheKeyBuilder builder);

    public abstract <T> T getValue(ResultRepresentation<T> representation) throws IOException;

    static final class NodeInputForOutput extends NodeInput {
        private NodeOutput output;

        public NodeInputForOutput(NodeOutput output) {
            this.output = output;
        }

        @Override
        public void replaceReferences(NodeOutput oldOutput, NodeOutput newOutput) {
            if (this.output == oldOutput) {
                this.output = newOutput;
            }
        }

        @Override
        public Collection<ExecutionNode> getNodeDependencies() {
            return Set.of(output.getNode());
        }

        @Override
        public void collectCacheKeyComponent(CacheKeyBuilder builder) {
            builder.addPath(getId(), output.getResultPath());
        }

        @Override
        public <T> T getValue(ResultRepresentation<T> representation) throws IOException {
            return output.getResultRepresentation(representation);
        }
    }
}
