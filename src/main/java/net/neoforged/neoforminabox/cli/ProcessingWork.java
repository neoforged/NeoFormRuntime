package net.neoforged.neoforminabox.cli;

import java.io.IOException;

@FunctionalInterface
public interface ProcessingWork {
    void run(ProcessingEnvironment environment) throws IOException, InterruptedException;
}
