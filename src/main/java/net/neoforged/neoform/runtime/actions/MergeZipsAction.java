package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// TODO: would be good to "merge" with MergeWithSourcesAction?
public class MergeZipsAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var classesFile = environment.getRequiredInputPath("classes");
        var extraClassesFile = environment.getRequiredInputPath("classes2");
        var output = environment.getOutputPath("output");

        try (var os = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {

            boolean first = true;
            for (var sourceZip : List.of(classesFile, extraClassesFile)) {
                try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(sourceZip)))) {
                    for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                        if (!first && entry.isDirectory()) {
                            // Skip directories in case they would be duplicated
                            // TODO: a bit crude
                            continue;
                        }
                        os.putNextEntry(entry);
                        in.transferTo(os);
                        os.closeEntry();
                    }
                }
                first = false;
            }
        }
    }
}
