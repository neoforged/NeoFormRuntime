package net.neoforged.neoforminabox.cli;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public record NeoForgeConfig() {

    public static NeoForgeConfig from(Path path) throws IOException {
        byte[] configContent;
        try (var zipFile = new ZipFile(path.toFile())) {
            var configEntry = zipFile.getEntry("config.json");
            if (configEntry == null || configEntry.isDirectory()) {
                throw new IOException("Neoforge config file config.json not found in " + path);
            }

            try (var in = zipFile.getInputStream(configEntry)) {
                configContent = in.readAllBytes();
            }
        }

        var gson = new GsonBuilder().create();
        var root = gson.fromJson(new StringReader(new String(configContent, StandardCharsets.UTF_8)), JsonObject.class);

        return new NeoForgeConfig();
    }
}
