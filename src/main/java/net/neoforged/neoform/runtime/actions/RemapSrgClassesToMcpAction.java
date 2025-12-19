package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.MCPMappingsZip;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import net.neoforged.srgutils.IMappingFile;
import net.neoforged.srgutils.IRenamer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class RemapSrgClassesToMcpAction extends ExternalJavaToolAction {
    private final Path mcpMappingsData;

    public RemapSrgClassesToMcpAction(Path mcpMappingsData) {
        super(ToolCoordinate.AUTO_RENAMING_TOOL);
        Objects.requireNonNull(mcpMappingsData, "MCP mapping data not provided");
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

        var mappingsFile = environment.getWorkspace().resolve("mappings.tsrg2");
        srgToMcpMappings.write(mappingsFile, IMappingFile.Format.TSRG2, false);

        setArgs(List.of(
                "--input", "{input}",
                "--output", "{output}",
                "--map", environment.getPathArgument(mappingsFile.toAbsolutePath())
        ));
        super.run(environment);
    }
}
