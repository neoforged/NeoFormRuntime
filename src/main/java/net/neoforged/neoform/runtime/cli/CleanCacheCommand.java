package net.neoforged.neoform.runtime.cli;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "clean-cache", description = "Perform cache maintenance")
public class CleanCacheCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Main commonOptions;

    @Override
    public Integer call() throws Exception {
        try (var cacheManager = commonOptions.createCacheManager()) {
            cacheManager.cleanUpAll();
        }

        return 0;
    }
}
