package net.neoforged.neoform.runtime.config.neoform;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;

/**
 * @param toolArtifact Maven artifact for the jar to run
 * @param repository   Maven repositories to download tool from
 * @param classpath    Newer replacement for "toolArtifact", mutually exclusive with it.
 * @param mainClass    Not allowed, when toolArtifact is used. Optional when classpath is a single item (assumes executable jar).
 */
public record NeoFormFunction(@SerializedName("version") String toolArtifact,
                              List<String> classpath,
                              @SerializedName("main_class") String mainClass,
                              @SerializedName("repo")
                              @Nullable URI repository,
                              List<String> args,
                              List<String> jvmargs) {
}
