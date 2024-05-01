package net.neoforged.neoforminabox.config.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.neoforged.neoforminabox.utils.MavenCoordinate;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public record NeoForgeConfig(
        int spec,
        @SerializedName("mcp") String neoformArtifact,
        @SerializedName("ats") String accessTransformersFolder,
        @SerializedName("patches") String patchesFolder,
        @SerializedName("sources") String sourcesArtifact,
        @SerializedName("universal") String universalArtifact,
        Map<String, JsonObject> runs,
        List<MavenCoordinate> libraries,
        List<String> modules
) {
    public static NeoForgeConfig from(ZipFile zipFile) throws IOException {
        byte[] configContent;
        var configEntry = zipFile.getEntry("config.json");
        if (configEntry == null || configEntry.isDirectory()) {
            throw new IOException("NeoForm config file config.json not found in " + zipFile.getName());
        }

        try (var in = zipFile.getInputStream(configEntry)) {
            configContent = in.readAllBytes();
        }

        var gson = new GsonBuilder()
                .registerTypeAdapter(MavenCoordinate.class, MavenCoordinate.TYPE_ADAPTER)
                .create();
        var root = gson.fromJson(new StringReader(new String(configContent, StandardCharsets.UTF_8)), JsonObject.class);

        return gson.fromJson(root, NeoForgeConfig.class);
    }
}
