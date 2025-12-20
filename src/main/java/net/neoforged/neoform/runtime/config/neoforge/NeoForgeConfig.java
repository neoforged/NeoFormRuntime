package net.neoforged.neoform.runtime.config.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import net.neoforged.neoform.runtime.config.neoform.NeoFormFunction;
import net.neoforged.neoform.runtime.utils.FilenameUtil;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public record NeoForgeConfig(
        int spec,
        @SerializedName("mcp") String neoformArtifact,
        @SerializedName("ats") String accessTransformersFolder,
        @SerializedName("binpatches") String binaryPatchesFile,
        @SerializedName("binpatcher") BinpatcherConfig binaryPatcherConfig,
        @SerializedName("patches") String patchesFolder,
        @SerializedName("sources") String sourcesArtifact,
        @SerializedName("universal") String universalArtifact,
        @SerializedName("patchesOriginalPrefix") @Nullable String basePathPrefix,
        @SerializedName("patchesModifiedPrefix") @Nullable String modifiedPathPrefix,
        Map<String, JsonObject> runs,
        List<MavenCoordinate> libraries,
        List<String> modules,
        @SerializedName("sass") List<String> sideAnnotationStrippers,
        // This was used in older MC versions (i.e. 1.12.2)
        @SerializedName("processor") @Nullable NeoFormFunction sourcePreProcessor
) {
    public static NeoForgeConfig from(ZipFile zipFile) throws IOException {
        byte[] configContent;
        var configEntry = zipFile.getEntry("config.json");
        if (configEntry == null || configEntry.isDirectory()) {
            throw new IOException("NeoForge config file config.json not found in " + zipFile.getName());
        }

        try (var in = zipFile.getInputStream(configEntry)) {
            configContent = in.readAllBytes();
        }

        var gson = new GsonBuilder()
                .registerTypeAdapter(MavenCoordinate.class, MavenCoordinate.TYPE_ADAPTER)
                .create();
        var root = gson.fromJson(new StringReader(new String(configContent, StandardCharsets.UTF_8)), JsonObject.class);

        var specVersion = root.getAsJsonPrimitive("spec").getAsInt();
        if (specVersion != 2) {
            throw new IOException("Unsupported NeoForge spec version: " + specVersion);
        }

        // Forge in 1.20.1 and before specify access transformers as an array of file paths,
        // while NeoForge 1.20.2+ points to a folder inside the ZIP instead.
        // 1.20.1 and before: "ats": ["ats/accesstransformer.cfg"]
        // 1.20.2 and later: "ats": "ats/"
        if (root.get("ats").isJsonArray()) {
            convertAccessTransformerPropertyFromForgeToNeoForge(root);
        }

        // Ensure that 'sass' is an empty list to avoid nullability issues
        if (!root.has("sass")) {
            root.add("sass", new JsonArray());
        }

        try {
            return gson.fromJson(root, NeoForgeConfig.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Failed to read NeoForge config from " + zipFile.getName(), e);
        }
    }

    private static void convertAccessTransformerPropertyFromForgeToNeoForge(JsonObject root) {
        var ats = root.get("ats").getAsJsonArray();
        var atFiles = new ArrayList<String>();
        for (var at : ats) {
            atFiles.add(at.getAsString());
        }

        var longestPrefix = FilenameUtil.longestCommonDirPrefix(atFiles);
        if (longestPrefix == null) {
            root.remove("ats");
        } else {
            root.addProperty("ats", longestPrefix);
        }
    }
}
