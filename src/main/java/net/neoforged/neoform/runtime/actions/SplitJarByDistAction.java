package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class SplitJarByDistAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputJar = environment.getRequiredInputPath("input");
        var commonJar = environment.getOutputPath("common");
        var clientOnlyJar = environment.getOutputPath("clientOnly");

        var clientEntries = readClientOnlyEntries(inputJar);

        try (
                var input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
                var common = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(commonJar)));
                var clientOnly = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(clientOnlyJar)))) {

            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                // TODO: the manifest might warrant some special handling to strip the dist flags

                // TODO: this doesn't handle NeoForge-added classes, such as com.mojang.blaze3d.opengl.GlStateManager$StencilState.class
                if (clientEntries.contains(entry.getName())) {
                    clientOnly.putNextEntry(entry);
                    input.transferTo(clientOnly);
                    clientOnly.closeEntry();
                } else {
                    common.putNextEntry(entry);
                    input.transferTo(common);
                    common.closeEntry();
                }
            }
        }
    }

    private Set<String> readClientOnlyEntries(Path inputJar) throws IOException {
        try (var zf = new ZipFile(inputJar.toFile())) {
            var manifestEntry = zf.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) {
                throw new IOException("MANIFEST.MF does not exist in the input jar, cannot read Minecraft-Dist attributes");
            }
            try (var is = zf.getInputStream(manifestEntry)) {
                var clientEntries = new HashSet<String>();
                Manifest manifest = new Manifest(is);
                for (var entry : manifest.getEntries().entrySet()) {
                    var attr = entry.getValue();
                    if ("client".equals(attr.getValue("Minecraft-Dist"))) {
                        clientEntries.add(entry.getKey());
                    }
                }
                return clientEntries;
            }
        }
    }
}
