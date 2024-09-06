package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.srgutils.IMappingFile;

import java.io.IOException;

/**
 * Chains two mapping files.
 * <p>
 * Assuming an input of A -> B (first) and B -> C (second), the chained result is A -> C.
 */
public class ChainMappingsAction implements ExecutionNodeAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var first = environment.getRequiredInputPath("first");
        var second = environment.getRequiredInputPath("second");

        var firstMappings = IMappingFile.load(first.toFile());
        var secondMappings = IMappingFile.load(second.toFile());

        firstMappings.chain(secondMappings).write(environment.getOutputPath("output"), IMappingFile.Format.TSRG2, false);
    }
}
