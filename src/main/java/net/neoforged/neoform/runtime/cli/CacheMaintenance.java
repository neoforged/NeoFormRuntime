package net.neoforged.neoform.runtime.cli;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "cache-maintenance", description = "Perform periodic cache maintenance if required")
public class CacheMaintenance implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @Override
    public Integer call() throws Exception {
        try (var cacheManager = commonOptions.createCacheManager()) {
            cacheManager.performMaintenance();
        }

        return 0;
    }
}
