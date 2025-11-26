package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * In older NeoForm processes, the output of the process is not a usable Minecraft mod jar.
 * <p>
 * The old process strips resources early (in the strip/stripClient/stripServer step), and then only deals with
 * classes. In the new process, resources are never stripped, and the Jar pre-processor will also add both the
 * Side-Manifest entries to the Jar Manifest, and add a neoforge.mods.toml to the Jar.
 */
public class CreateMinecraftModJarAction extends BuiltInAction {
    private static final String MOD_MANIFEST_NAME = "META-INF/neoforge.mods.toml";

    static final long STABLE_TIMESTAMP = 0x386D4380; //01/01/2000 00:00:00 java 8 breaks when using 0.

    private final String minecraftVersion;

    public CreateMinecraftModJarAction(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {

        var classesFile = environment.getRequiredInputPath("classes");
        var resourcesFile = environment.getInputPath("resources");
        var output = environment.getOutputPath("output");

        try (var os = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            boolean modManifestCopied = false;

            try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(classesFile)))) {
                for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    os.putNextEntry(entry);
                    in.transferTo(os);
                    os.closeEntry();
                    if (entry.getName().equals(MOD_MANIFEST_NAME)) {
                        modManifestCopied = true;
                    }
                }
            }

            if (resourcesFile != null) {
                try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(resourcesFile)))) {
                    for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                        os.putNextEntry(entry);
                        in.transferTo(os);
                        os.closeEntry();
                        if (entry.getName().equals(MOD_MANIFEST_NAME)) {
                            modManifestCopied = true;
                        }
                    }
                }
            }

            // If no META-INF/neoforge.mods.toml was copied over, we now inject a new one.
            if (!modManifestCopied) {
                appendModManifest(os);
            }
        }
    }

    // This is based on installertools.
    private void appendModManifest(ZipOutputStream os) throws IOException {
        String modManifest = "modLoader=\"minecraft\"\n" +
                "license=\"Minecraft EULA\"\n" +
                "[[mods]]\n" +
                "modId=\"minecraft\"\n" +
                "version=\"" + minecraftVersion + "\"\n" +
                "displayName=\"Minecraft\"\n" +
                "authors=\"Mojang Studios\"\n" +
                "description=\"\"\n";

        var entry = new ZipEntry(MOD_MANIFEST_NAME);
        entry.setTime(STABLE_TIMESTAMP);
        os.putNextEntry(entry);
        os.write(modManifest.getBytes(StandardCharsets.UTF_8));
        os.closeEntry();
    }
}
