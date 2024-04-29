package net.neoforged.neoforminabox.manifests;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record MinecraftVersionManifest(String id, Map<String, MinecraftDownload> downloads,
                                       List<MinecraftLibrary> libraries) {
    public static MinecraftVersionManifest from(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return new Gson().fromJson(reader, MinecraftVersionManifest.class);
        }
    }
}
