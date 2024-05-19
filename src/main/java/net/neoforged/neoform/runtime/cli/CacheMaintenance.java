package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.cache.CacheManager;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "cache-maintenance", description = "Perform periodic cache maintenance if required")
public class CacheMaintenance implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @Override
    public Integer call() throws Exception {
        try (var cacheManager = new CacheManager(commonOptions.homeDir)) {
            cacheManager.setVerbose(commonOptions.verbose);

            cacheManager.performMaintenance();
        }

        return 0;
    }
}
