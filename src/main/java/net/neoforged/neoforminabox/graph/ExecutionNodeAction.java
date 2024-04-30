package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.cli.ProcessingEnvironment;

import java.io.IOException;

public interface ExecutionNodeAction {
    void run(ProcessingEnvironment environment) throws IOException, InterruptedException;

    /**
     * Compute the component of a cache key that describes the action itself, irrespective of its inputs.
     * For an external tool, this might encompass the precise version of that tool, and the arguments it is given.
     */
    void computeCacheKey(CacheKeyBuilder ck);
}
