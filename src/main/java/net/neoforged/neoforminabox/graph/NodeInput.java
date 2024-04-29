package net.neoforged.neoforminabox.graph;

import java.util.Collection;
import java.util.Set;

public interface NodeInput {
    Collection<ExecutionNode> getNodeDependencies();

    <T> T resolve(Class<T> resultClass);

    record NodeInputForOutput(ExecutionNode node, NodeOutput output) implements NodeInput {
        @Override
        public Collection<ExecutionNode> getNodeDependencies() {
            return Set.of(node);
        }

        @Override
        public <T> T resolve(Class<T> resultClass) {
            if (output.getResult() == null) {
                throw new IllegalStateException("Result for output " + output + " has not yet been set");
            }
            if (!resultClass.isAssignableFrom(output.type().getResultClass())) {
                throw new IllegalStateException("Trying to get " + resultClass + " output from output " + output + " which is of type " + output.type());
            }
            return resultClass.cast(output.getResult());
        }
    }
}
