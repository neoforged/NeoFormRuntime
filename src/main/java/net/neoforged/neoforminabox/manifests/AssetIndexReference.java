package net.neoforged.neoforminabox.manifests;

import com.google.gson.annotations.SerializedName;
import net.neoforged.neoforminabox.downloads.DownloadSpec;

import java.net.URI;

public record AssetIndexReference(String id, String sha1, int size, long totalSize,
                                  @SerializedName("url") URI uri) implements DownloadSpec {
}
