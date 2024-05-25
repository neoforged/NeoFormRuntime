package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.LoggerCategory;

import java.nio.file.Paths;

/**
 * A built-in action contributes a component to the cache key of a node that encompasses
 * the version of this tool itself.
 */
public abstract class BuiltInAction implements ExecutionNodeAction {
    protected static final Logger LOG = Logger.create(LoggerCategory.EXECUTION);

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        try {
            var location = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            ck.addPath("action implementation", location);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
