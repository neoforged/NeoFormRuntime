package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Copies a NeoForm data source (a file embedded in the NeoForm config zip) to a node output,
 * making it available as an input to downstream nodes in the execution graph.
 */
public class ExtractNeoFormDataAction implements ExecutionNodeAction {
    private final String dataKey;

    public ExtractNeoFormDataAction(String dataKey) {
        this.dataKey = dataKey;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        Files.copy(environment.extractData(dataKey), environment.getOutputPath("output"));
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        ExecutionNodeAction.super.computeCacheKey(ck);
        ck.add("data key", dataKey);
    }
}
