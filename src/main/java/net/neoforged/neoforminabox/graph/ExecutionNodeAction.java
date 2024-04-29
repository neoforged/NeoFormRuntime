package net.neoforged.neoforminabox.graph;

import net.neoforged.neoforminabox.cli.ProcessingEnvironment;

import java.io.IOException;

public interface ExecutionNodeAction {
    void run(ProcessingEnvironment environment) throws IOException, InterruptedException;
}
