package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.cache.CacheManager;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "clean-cache", description = "Perform cache maintenance")
public class CleanCacheCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    // Delete content last used a month ago
    @CommandLine.Option(names = "--max-age")
    long maxAgeInHours = 24 * 31;

    // Try to keep the cache below 1 gigabyte
    @CommandLine.Option(names = "--max-size")
    long maxTotalSize = 512 * 1024 * 1024;

    @Override
    public Integer call() throws Exception {
        try (var cacheManager = new CacheManager(commonOptions.homeDir)) {
            cacheManager.cleanUp(maxAgeInHours, maxTotalSize);
        }

        return 0;
    }
}
