package net.neoforged.neoform.runtime.graph.transforms;

import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Allows the action of an existing node to be modified.
 */
public class ModifyAction<T extends ExecutionNodeAction> extends GraphTransform {
    private final String nodeId;
    private final Class<T> expectedActionType;
    private final Function<T, ExecutionNodeAction> actionTransform;

    public ModifyAction(String nodeId, Class<T> expectedActionType, Consumer<T> actionTransform) {
        this(nodeId, expectedActionType, action -> {
            actionTransform.accept(action);
            return action;
        });
    }

    public ModifyAction(String nodeId, Class<T> expectedActionType, Function<T, ExecutionNodeAction> actionTransform) {
        this.nodeId = nodeId;
        this.expectedActionType = expectedActionType;
        this.actionTransform = actionTransform;
    }

    @Override
    public void apply(NeoFormEngine engine, ExecutionGraph graph) {
        var node = graph.getNode(nodeId);
        if (node == null) {
            throw new IllegalStateException("Trying to customize node " + nodeId + " but it does not exist.");
        }

        if (!expectedActionType.isInstance(node.action())) {
            throw new IllegalStateException("Expected node " + nodeId + " to have an action of type " + expectedActionType + ", but it has: " + node.action().getClass().getName());
        }

        var nodeAction = expectedActionType.cast(node.action());
        node.setAction(actionTransform.apply(nodeAction));
    }
}
