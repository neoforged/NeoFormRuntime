package net.neoforged.neoform.runtime.downloads;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AssetDownloadResult(Path assetRoot, String assetIndexId) {

    public void writeAsProperties(Path destination) throws IOException {
        var properties = new Properties();
        properties.put("assets_root", assetRoot.toAbsolutePath().toString());
        properties.put("asset_index", assetIndexId);
        try (var out = new BufferedOutputStream(Files.newOutputStream(destination))) {
            properties.store(out, null);
        }
    }

    public void writeAsJson(Path destination) throws IOException {
        var jsonObject = new JsonObject();
        jsonObject.addProperty("assets", assetRoot.toAbsolutePath().toString());
        jsonObject.addProperty("asset_index", assetIndexId);
        var jsonString = new Gson().toJson(jsonObject);
        Files.writeString(destination, jsonString, StandardCharsets.UTF_8);
    }

}
