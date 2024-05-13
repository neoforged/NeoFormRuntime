package net.neoforged.neoform.runtime.graph;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.IOException;

public interface ExecutionNodeAction {
    void run(ProcessingEnvironment environment) throws IOException, InterruptedException;

    /**
     * Compute the component of a cache key that describes the action itself, irrespective of its inputs.
     * For an external tool, this might encompass the precise version of that tool, and the arguments it is given.
     */
    default void computeCacheKey(CacheKeyBuilder ck) {
        ck.add("node action class", getClass().getName());
    }
}
