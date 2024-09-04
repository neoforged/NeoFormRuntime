package net.neoforged.neoform.runtime.manifests;

import com.google.gson.annotations.SerializedName;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;

import java.net.URI;

public record AssetIndexReference(String id, @SerializedName("sha1") String checksum, int size, long totalSize,
                                  @SerializedName("url") URI uri) implements DownloadSpec {
    @Override
    public String checksumAlgorithm() {
        return "sha1";
    }
}
