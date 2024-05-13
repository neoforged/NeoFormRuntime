package net.neoforged.neoform.runtime.engine;

import java.io.IOException;

@FunctionalInterface
public interface ProcessingWork {
    void run(ProcessingEnvironment environment) throws IOException, InterruptedException;
}
