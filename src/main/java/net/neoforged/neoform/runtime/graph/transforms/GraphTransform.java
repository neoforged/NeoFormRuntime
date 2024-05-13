package net.neoforged.neoform.runtime.graph.transforms;

import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.graph.ExecutionGraph;

public abstract class GraphTransform {
    public abstract void apply(NeoFormEngine engine, ExecutionGraph graph);
}

