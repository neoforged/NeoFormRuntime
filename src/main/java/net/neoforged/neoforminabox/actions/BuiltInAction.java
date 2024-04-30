package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cache.CacheKeyBuilder;
import net.neoforged.neoforminabox.graph.ExecutionNodeAction;

import java.nio.file.Paths;

/**
 * A built-in action contributes a component to the cache key of a node that encompasses
 * the version of this tool itself.
 */
public abstract class BuiltInAction implements ExecutionNodeAction {
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
