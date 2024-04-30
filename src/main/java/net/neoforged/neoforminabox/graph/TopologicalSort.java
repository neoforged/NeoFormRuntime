package net.neoforged.neoforminabox.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

final class TopologicalSort {
    private TopologicalSort() {
    }

    /**
     * Simplifies topological sort used only for debugging purposes.
     * This is just a implementation of <a href="https://en.wikipedia.org/wiki/Topological_sorting">Kahn's algorithm</a>.
     */
    public static List<ExecutionNode> topologicalSort(ExecutionGraph graph) throws IllegalArgumentException {
        final Queue<ExecutionNode> queue = new ArrayDeque<>();
        final Map<ExecutionNode, Integer> degrees = new HashMap<>();
        final List<ExecutionNode> results = new ArrayList<>();

        // We only know about incoming edges, so we compute the succesors first to quickly have access to those edges from the predecessor
        Map<ExecutionNode, Set<ExecutionNode>> successors = new IdentityHashMap<>();
        for (var node : graph.getNodes()) {
            successors.putIfAbsent(node, Collections.newSetFromMap(new IdentityHashMap<>()));
            for (var predecessor : node.getPredecessors()) {
                successors.computeIfAbsent(predecessor, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(node);
            }
        }

        for (var node : graph.getNodes()) {
            // degree: number of incoming edges
            final int degree = node.getPredecessors().size();
            if (degree == 0) {
                queue.add(node);
            } else {
                degrees.put(node, degree);
            }
        }

        while (!queue.isEmpty()) {
            var current = queue.remove();
            results.add(current);
            for (var successor : successors.get(current)) {
                final int updated = degrees.compute(successor, (node, degree) -> Objects.requireNonNull(degree, () -> "Invalid degree present for " + node) - 1);
                if (updated == 0) {
                    queue.add(successor);
                    degrees.remove(successor);
                }
            }
        }

        if (!degrees.isEmpty()) {
            throw new IllegalStateException("The graph has cycles");
        }

        return results;
    }
}
