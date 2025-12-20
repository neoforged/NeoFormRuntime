package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.MCPMappingsZip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class RemapSrgSourcesToMcpAction extends RemapSrgSourcesAction {
    private final Path mcpMappingsData;

    public RemapSrgSourcesToMcpAction(Path mcpMappingsData) {
        Objects.requireNonNull(mcpMappingsData, "MCP mappings data not provided");
        this.mcpMappingsData = mcpMappingsData;
    }

    @Override
    protected Map<String, String> buildSrgToOfficialMap(ProcessingEnvironment environment) throws IOException {
        return MCPMappingsZip.from(mcpMappingsData).combinedMappings();
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addPath("mcp mappings data", this.mcpMappingsData);
    }
}
