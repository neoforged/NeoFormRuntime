package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Binarypatcher only outputs the classes that were patched,
 * and this action will copy the unpatched classes into such a jar.
 */
public class CopyUnpatchedClassesAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {

        var patchedClassesFile = environment.getRequiredInputPath("patched");
        var unpatchedClassesFile = environment.getRequiredInputPath("unpatched");
        var output = environment.getOutputPath("output");

        try (var os = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {

            var patchedNames = new HashSet<String>();
            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(patchedClassesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    patchedNames.add(entry.getName());
                    os.putNextEntry(entry);
                    in.transferTo(os);
                    os.closeEntry();
                }
            }

            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(unpatchedClassesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (!entry.getName().endsWith(".class")) {
                        continue; // Only copy .class files
                    }
                    if (patchedNames.contains(entry.getName())) {
                        continue; // Skip classes that were patched
                    }

                    os.putNextEntry(entry);
                    in.transferTo(os);
                    os.closeEntry();
                }
            }
        }
    }
}
