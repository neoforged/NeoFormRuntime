package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class SpiltDistAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputPath = environment.getRequiredInputPath("input");
        var commonPath = environment.getOutputPath("common");
        var clientPath = environment.getOutputPath("client");
        try (var inputJar = new JarFile(inputPath.toFile());
             var input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputPath)));
             var commonTarget = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(commonPath)));
             var clientTarget = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(clientPath)))
        ) {
            var manifest = inputJar.getManifest();
            var distName = new Attributes.Name("Minecraft-Dist");
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }

                var name = entry.getName();
                Attributes fileEntry = null;
                if (name.endsWith(".class")) {
                    fileEntry = manifest.getEntries().get(name);
                } else if (name.endsWith(".java")) {
                    fileEntry = manifest.getEntries().get(name.replace(".java", ".class"));
                }
                String dist = null;

                if (fileEntry != null) {
                    dist = fileEntry.getValue(distName);
                } else if (name.startsWith("net/neoforged/neoforge/client")) {
                    dist = "client";
                }

                if ("client".equals(dist)) {
                    clientTarget.putNextEntry(entry);
                    input.transferTo(clientTarget);
                    clientTarget.closeEntry();
                } else {
                    commonTarget.putNextEntry(entry);
                    input.transferTo(commonTarget);
                    commonTarget.closeEntry();
                }
            }
        }
    }

}
