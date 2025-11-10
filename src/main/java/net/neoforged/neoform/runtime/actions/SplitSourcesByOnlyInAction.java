package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// TODO: not great; we could just read the manifest from another input jar and replace .class by .java
public class SplitSourcesByOnlyInAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputJar = environment.getRequiredInputPath("input");
        var commonJar = environment.getOutputPath("common");
        var clientOnlyJar = environment.getOutputPath("clientOnly");

        try (
                var input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
                var common = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(commonJar)));
                var clientOnly = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(clientOnlyJar)))) {

            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                var bytes = input.readAllBytes();
                if (new String(bytes).contains("\n@OnlyIn(Dist.CLIENT)")) {
                    clientOnly.putNextEntry(entry);
                    clientOnly.write(bytes);
                    clientOnly.closeEntry();
                } else {
                    common.putNextEntry(entry);
                    common.write(bytes);
                    common.closeEntry();
                }
            }
        }
    }
}
