package net.neoforged.neoform.runtime.manifests;

import com.google.gson.annotations.SerializedName;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

public record MinecraftDownload(@SerializedName("sha1") String checksum, int size,
                                @SerializedName("url") URI uri, @Nullable String path) implements DownloadSpec {
    @Override
    public String checksumAlgorithm() {
        return "sha1";
    }
}
