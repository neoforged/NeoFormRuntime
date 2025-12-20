package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.srgutils.IMappingFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Takes a zip-file of Java sources and replaces SRG identifiers with
 * identifiers from a mapping file. Presumes there are "merged mappings"
 * available already.
 */
public class RemapSrgSourcesAction implements ExecutionNodeAction {
    private static final Pattern SRG_FINDER = Pattern.compile("[fF]unc_\\d+_[a-zA-Z_]+|m_\\d+_|[fF]ield_\\d+_[a-zA-Z_]+|f_\\d+_");

    static IMappingFile buildSrgToOfficialMappingFile(ProcessingEnvironment environment) throws IOException {
        var officialMappingsPath = environment.getRequiredInputPath("officialMappings");
        var mergeMappingsPath = environment.getRequiredInputPath("mergedMappings");

        // So this essentially maps back from what we have applied in `sources` to obfuscated
        // and then from obfuscated fully to official.
        var srgMappings = IMappingFile.load(mergeMappingsPath.toFile()).reverse();
        var officialMappings = IMappingFile.load(officialMappingsPath.toFile()).reverse();
        return srgMappings.chain(officialMappings);
    }

    protected Map<String, String> buildSrgToOfficialMap(ProcessingEnvironment environment) throws IOException {
        var srgToOfficial = buildSrgToOfficialMappingFile(environment);
        var srgNamesToOfficial = new HashMap<String, String>();
        for (var mappedClass : srgToOfficial.getClasses()) {
            for (var mappedField : mappedClass.getFields()) {
                srgNamesToOfficial.put(mappedField.getOriginal(), mappedField.getMapped());
            }
            for (var mappedMethod : mappedClass.getMethods()) {
                srgNamesToOfficial.put(mappedMethod.getOriginal(), mappedMethod.getMapped());
            }
        }
        return srgNamesToOfficial;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var srgNamesToOfficial = this.buildSrgToOfficialMap(environment);

        var sourcesPath = environment.getRequiredInputPath("sources");
        var outputPath = environment.getOutputPath("output");

        try (var zipIn = new ZipInputStream(new BufferedInputStream(Files.newInputStream(sourcesPath)));
             var zipOut = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputPath)))) {
            for (var entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                zipOut.putNextEntry(entry);

                if (entry.getName().endsWith(".java")) {
                    var sourceCode = new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                    var mappedSource = mapSourceCode(sourceCode, srgNamesToOfficial);
                    zipOut.write(mappedSource.getBytes(StandardCharsets.UTF_8));
                } else {
                    zipIn.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }
        }
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

    @FunctionalInterface
    public interface MappingProvider {
        Map<String, String> getMappings(ProcessingEnvironment environment) throws IOException;
    }
}
