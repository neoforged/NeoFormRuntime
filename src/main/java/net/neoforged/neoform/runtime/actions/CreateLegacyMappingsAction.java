package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.graph.ExecutionNodeAction;
import net.neoforged.srgutils.IMappingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates different mapping files used by the legacy toolchain:
 * <ul>
 *  <li>a {@code officialToSrg} TSRGv2 file that maps official names to SRG names</li>
 *  <li>a {@code srgToOfficial} TSRGv1 file (to please Mixin) that maps SRG names to official names</li>
 *  <li>a {@code csvMappings} zip file containing 2 csv files that map SRG names to official names</li>
 * </ul>
 */
public class CreateLegacyMappingsAction implements ExecutionNodeAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var first = environment.getRequiredInputPath("officialToObf");
        var second = environment.getRequiredInputPath("obfToSrg");

        var firstMappings = IMappingFile.load(first.toFile());
        var secondMappings = IMappingFile.load(second.toFile());

        var officialToSrg = firstMappings.chain(secondMappings);
        officialToSrg.write(environment.getOutputPath("officialToSrg"), IMappingFile.Format.TSRG2, false);

        var srgToOfficial = officialToSrg.reverse();
        srgToOfficial.write(environment.getOutputPath("srgToOfficial"), IMappingFile.Format.TSRG, false);

        try (var zipCsv = new ZipOutputStream(Files.newOutputStream(environment.getOutputPath("csvMappings")))) {
            writeCsv(zipCsv, "methods.csv", srgToOfficial.getClasses().stream()
                    .flatMap(c -> c.getMethods().stream()).filter(c -> c.getOriginal().startsWith("m_")));
            writeCsv(zipCsv, "fields.csv", srgToOfficial.getClasses().stream()
                    .flatMap(c -> c.getFields().stream()).filter(c -> c.getOriginal().startsWith("f_")));
        }
    }

    private static void writeCsv(ZipOutputStream stream, String name, Stream<? extends IMappingFile.INode> nodes) throws IOException {
        stream.putNextEntry(new ZipEntry(name));
        stream.write(("searge,name,side,desc\n" + nodes.map(n -> n.getOriginal() + "," + n.getMapped() + ",0,").collect(Collectors.joining("\n"))).getBytes(StandardCharsets.UTF_8));
        stream.closeEntry();
    }
}
