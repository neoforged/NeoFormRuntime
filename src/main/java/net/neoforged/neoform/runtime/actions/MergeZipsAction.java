package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipInputStream;

public class MergeZipsAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var classesFile = environment.getRequiredInputPath("classes");
        var extraClassesFile = environment.getRequiredInputPath("classes2");
        var output = environment.getOutputPath("output");

        // Copy the largest jar then use NIO to insert extra entries.
        // This is faster than working with ZipFile streams which inflate and deflate all the entries.
        Files.copy(classesFile, output);
        try (var zfs = FileSystems.newFileSystem(output, Map.of("create", false))) {
            var zfsRoot = zfs.getPath("/");

            // Copy the extra classes to the output zip
            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(extraClassesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    var targetPath = zfsRoot.resolve(entry.getName());
                    Files.copy(in, targetPath);
                }
            }
        }
    }
}
