package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// TODO: de-duplicate with MergeWithSourcesAction
/**
 * Merges the Java sources with the compiled output into a single combined file to facilitate source
 * lookup in IntelliJ.
 */
public class MergeWithResourcesAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {

        var classesFile = environment.getRequiredInputPath("classes");
        var resourcesFile = environment.getRequiredInputPath("resources");
        var output = environment.getOutputPath("output");

        try (var os = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {

            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(classesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    os.putNextEntry(entry);
                    in.transferTo(os);
                    os.closeEntry();
                }
            }

            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(resourcesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    try {
                        os.putNextEntry(entry);
                        in.transferTo(os);
                        os.closeEntry();
                    } catch (ZipException e) {
//                        if (!e.getMessage().startsWith("duplicate entry:")) {
                            throw e;
//                        }
                    }
                }
            }
        }
    }
}
