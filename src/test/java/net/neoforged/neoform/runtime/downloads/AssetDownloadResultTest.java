package net.neoforged.neoform.runtime.downloads;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AssetDownloadResultTest {

    @TempDir
    Path tempDir;

    AssetDownloadResult result;

    @BeforeEach
    void setUp() {
        result = new AssetDownloadResult(tempDir.resolve("äöäpäö bnlanil"), "123");
    }

    @Test
    void testWriteAsProperties() throws Exception {
        var tempFile = tempDir.resolve("asset.properties");
        result.writeAsProperties(tempFile);

        var p = new Properties();
        try (var in = Files.newInputStream(tempFile)) {
            p.load(in);
        }
        assertThat(p).containsOnly(
                Map.entry("asset_index", "123"),
                Map.entry("assets_root", result.assetRoot().toString())
        );
    }

    @Test
    void testWriteAsJson() throws Exception {
        var tempFile = tempDir.resolve("asset.json");
        result.writeAsJson(tempFile);

        JsonObject o;
        try (var in = Files.newBufferedReader(tempFile, StandardCharsets.UTF_8)) {
            o = new Gson().fromJson(in, JsonObject.class);
        }

        JsonObject expected = new JsonObject();
        expected.addProperty("asset_index", "123");
        expected.addProperty("assets", result.assetRoot().toString());
        assertThat(o).isEqualTo(expected);
    }
}