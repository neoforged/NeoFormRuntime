package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.neoform.runtime.utils.MCPMappingsZip;
import net.neoforged.srgutils.IMappingFile;
import net.neoforged.srgutils.IRenamer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class GenerateMCPSrgFilesAction implements ExecutionNodeAction {
    private final Path mcpMappingsData;

    public GenerateMCPSrgFilesAction(Path mcpMappingsData) {
        Objects.requireNonNull(mcpMappingsData);
        this.mcpMappingsData = mcpMappingsData;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        // We need to generate a srg->mcp mappings file. To do this we use the obf->srg mappings, reverse them,
        // and then replace the obf names with the mcp ones.
        var obfToSrgFile = environment.extractData("mappings");
        var mappingsZip = MCPMappingsZip.from(mcpMappingsData);

        var srgToMcpMappings = IMappingFile.load(obfToSrgFile.toFile()).reverse().rename(new IRenamer() {
            @Override
            public String rename(IMappingFile.IField value) {
                return mappingsZip.fieldMappings().getOrDefault(value.getOriginal(), value.getOriginal());
            }

            @Override
            public String rename(IMappingFile.IMethod value) {
                return mappingsZip.methodMappings().getOrDefault(value.getOriginal(), value.getOriginal());
            }
        });

        // Now create the expected toolchain outputs
        srgToMcpMappings.reverse().write(environment.getOutputPath("officialToSrg"), IMappingFile.Format.TSRG, false);
        srgToMcpMappings.write(environment.getOutputPath("srgToOfficial"), IMappingFile.Format.SRG, false);
        Files.copy(mcpMappingsData, environment.getOutputPath("csvMappings"));
    }
}
