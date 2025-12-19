package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class RemapSrgClassesToMcpAction extends ExternalJavaToolAction {
    public RemapSrgClassesToMcpAction(Path mcpMappingsData) {
        super(ToolCoordinate.AUTO_RENAMING_TOOL);
        Objects.requireNonNull(mcpMappingsData, "MCP mapping data not provided");
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        setArgs(List.of(
                "--input", "{input}",
                "--output", "{output}",
                "--map", environment.getInputPath("srgToMcpMappings").toAbsolutePath().toString()
        ));
        super.run(environment);
    }
}
