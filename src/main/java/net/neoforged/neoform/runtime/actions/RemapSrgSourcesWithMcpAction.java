package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Takes a zip-file of Java sources and replaces SRG identifiers with
 * identifiers from a mapping file.
 * This version of {@link RemapSrgSourcesAction} uses MCP Mappings data for
 * the intermediate->named mapping.
 * The mappings are loaded from an MCP mappings ZIP, which contains CSV files with mappings for SRG->Named.
 */
public class RemapSrgSourcesWithMcpAction implements ExecutionNodeAction {
    private static final Pattern SRG_FINDER = Pattern.compile("[fF]unc_\\d+_[a-zA-Z_]+|m_\\d+_|[fF]ield_\\d+_[a-zA-Z_]+|f_\\d+_");

    private final Path mcpMappingsData;

    public RemapSrgSourcesWithMcpAction(Path mcpMappingsData) {
        this.mcpMappingsData = mcpMappingsData;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var mappings = new HashMap<String, String>();

        try (var zf = new ZipFile(mcpMappingsData.toFile())) {
            loadMappingsCsv(zf, "fields.csv", mappings);
            loadMappingsCsv(zf, "methods.csv", mappings);
        }

        var sourcesPath = environment.getRequiredInputPath("sources");
        var outputPath = environment.getOutputPath("output");

        try (var zipIn = new ZipInputStream(new BufferedInputStream(Files.newInputStream(sourcesPath)));
             var zipOut = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputPath)))) {
            for (var entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                zipOut.putNextEntry(entry);

                if (entry.getName().endsWith(".java")) {
                    var sourceCode = new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                    var mappedSource = mapSourceCode(sourceCode, mappings);
                    zipOut.write(mappedSource.getBytes(StandardCharsets.UTF_8));
                } else {
                    zipIn.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }
        }
    }

    // A very rudimentary parser for the CSV files inside the mappings ZIP.
    // Ignores everything except the first two columns.
    // Example:
    // searge,name,side,desc
    // field_100013_f,isPotionDurationMax,0,"True if potion effect duration is at maximum, false otherwise."
    private void loadMappingsCsv(ZipFile zf, String filename, Map<String, String> mappings) throws IOException {
        var entry = zf.getEntry(filename);
        if (entry == null) {
            throw new IllegalStateException("MCP mappings ZIP file is missing entry " + filename);
        }

        try (var reader = new BufferedReader(new InputStreamReader(zf.getInputStream(entry)))) {
            var header = reader.readLine();
            if (!header.startsWith("searge,name,")) {
                throw new IOException("Invalid header for Mappings CSV: " + filename);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                var parts = line.split(",", 3);
                if (parts.length < 2) {
                    continue;
                }
                String seargeName = parts[0];
                String name = parts[1];
                mappings.put(seargeName, name);
            }
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        ExecutionNodeAction.super.computeCacheKey(ck);
        ck.addPath("mcp mappings data", mcpMappingsData);
    }

    private static String mapSourceCode(String sourceCode, Map<String, String> srgNamesToOfficial) {
        var m = SRG_FINDER.matcher(sourceCode);
        return m.replaceAll(matchResult -> {
            var matched = matchResult.group();
            // Some will be unmapped
            var mapped = srgNamesToOfficial.getOrDefault(matched, matched);
            return Matcher.quoteReplacement(mapped);
        });
    }
}
