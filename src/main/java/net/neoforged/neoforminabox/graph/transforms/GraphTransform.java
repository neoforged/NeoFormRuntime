package net.neoforged.neoforminabox.graph.transforms;

import net.neoforged.neoforminabox.engine.NeoFormEngine;
import net.neoforged.neoforminabox.graph.ExecutionGraph;

public abstract class GraphTransform {
    public abstract void apply(NeoFormEngine engine, ExecutionGraph graph);
}

