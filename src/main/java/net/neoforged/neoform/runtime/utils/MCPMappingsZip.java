package net.neoforged.neoform.runtime.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public record MCPMappingsZip(Map<String, String> fieldMappings, Map<String, String> methodMappings) {
    public static MCPMappingsZip from(Path zipFilePath) throws IOException {
        try (var zf = new ZipFile(zipFilePath.toFile())) {
            var fields = loadMappingsCsv(zf, "fields.csv");
            var methods = loadMappingsCsv(zf, "methods.csv");
            return new MCPMappingsZip(fields, methods);
        }
    }

    // A very rudimentary parser for the CSV files inside the mappings ZIP.
    // Ignores everything except the first two columns.
    // Example:
    // searge,name,side,desc
    // field_100013_f,isPotionDurationMax,0,"True if potion effect duration is at maximum, false otherwise."
    private static Map<String, String> loadMappingsCsv(ZipFile zf, String filename) throws IOException {
        Map<String, String> mappings = new HashMap<>();

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

        return Map.copyOf(mappings);
    }

    public Map<String, String> combinedMappings() {
        Map<String, String> combinedMap = HashMap.newHashMap(fieldMappings.size() + methodMappings.size());
        combinedMap.putAll(fieldMappings);
        combinedMap.putAll(methodMappings);
        return combinedMap;
    }
}
