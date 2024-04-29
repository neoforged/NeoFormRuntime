package net.neoforged.neoforminabox.config.neoform;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;

/**
 * @param toolArtifact Maven artifact for the jar to run
 * @param repository   Maven repositories to download tool from
 */
public record NeoFormFunction(@SerializedName("version") String toolArtifact,
                       @SerializedName("repo")
                       @Nullable URI repository,
                       List<String> args,
                       List<String> jvmargs) {
}
