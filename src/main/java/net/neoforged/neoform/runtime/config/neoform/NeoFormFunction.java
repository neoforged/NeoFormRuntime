package net.neoforged.neoform.runtime.config.neoform;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;

/**
 * @param toolArtifact Mutually exclusive with mainClass/classpath since this is a legacy field. Maven coordinate of the executable jar.
 * @param mainClass  The Java main class to run.
 * @param classpath  Maven artifacts for the jar to run
 * @param repository Maven repositories to download tool from
 */
public record NeoFormFunction(@SerializedName("version") String toolArtifact,
                              @SerializedName("main_class") String mainClass,
                              @SerializedName("classpath") List<String> classpath,
                              @SerializedName("repo")
                              @Nullable URI repository,
                              List<String> args,
                              List<String> jvmargs) {
}
