package net.neoforged.neoform.runtime.cli;

/**
 * A collection of the standard result ids for the NeoForm/NeoForge graph.
 */
public final class ResultIds {
    /**
     * The recompilable Minecraft source code as a source zip.
     */
    public static final String GAME_SOURCES = "gameSources";
    /**
     * The recompiled Minecraft source code as a jar file.
     */
    public static final String GAME_JAR = "gameJar";
    /**
     * The recompiled Minecraft source code as a jar file, with sources merge into it to allow source browsing
     * in IntelliJ (which doesn't support attaching sources to a file dependency in Gradle).
     */
    public static final String GAME_JAR_WITH_SOURCES = "gameJarWithSources";

    /**
     * Same as {@link #GAME_SOURCES}, but NeoForge sources are merged into the source zip file.
     * Should be considered deprecated as NeoForge should be added separately to the classpath.
     */
    public static final String GAME_SOURCES_WITH_NEOFORGE = "gameSourcesWithNeoForge";
    /**
     * Same as {@link #GAME_JAR}, but .class files from the NeoForge universal jar are merged into the jar file.
     * Should be considered deprecated as NeoForge should be added separately to the classpath.
     */
    public static final String GAME_JAR_WITH_NEOFORGE = "gameJarWithNeoForge";
    /**
     * Same as {@link #GAME_JAR_WITH_SOURCES}, but both the NeoForge sources and universal jar are merged into the
     * jar file.
     * Should be considered deprecated as NeoForge should be added separately to the classpath.
     */
    public static final String GAME_JAR_WITH_SOURCES_AND_NEOFORGE = "gameJarWithSourcesAndNeoForge";

    /**
     * Similar to {@link #GAME_JAR} as it contains the compiled game classes, but they were not created
     * using the NeoForm decompile+recompile workflow. Rather they use original artifacts with binary patches
     * applied (for NeoForge) or just remapped (for NeoForm only mode).
     */
    public static final String GAME_JAR_NO_RECOMP = "gameJarNoRecomp";
    /**
     * Same as {@link #GAME_JAR_NO_RECOMP}, but with NeoForge merged into the artifact. It is the same relationship
     * as between {@link #GAME_JAR} and {@link #GAME_JAR_WITH_NEOFORGE}.
     */
    public static final String GAME_JAR_NO_RECOMP_WITH_NEOFORGE = "gameJarNoRecompWithNeoForge";

    /**
     * The Jar file of the Vanilla artifact (client, server or joined) after it has been deobfuscated.
     * In legacy Forge processes, the mapping from intermediary to named should have been applied as well.
     */
    public static final String VANILLA_DEOBFUSCATED = "vanillaDeobfuscated";
    /**
     * A TSRG mapping file to map from developer-facing to intermediary names.
     * Only available in legacy processes where artifacts, patches and runtime use an intermediary naming scheme.
     */
    public static final String NAMED_TO_INTERMEDIARY_MAPPING = "namedToIntermediaryMapping";
    /**
     * A SRG mapping file to map from intermediary-names to developer-facing names.
     * Only available in legacy processes where artifacts, patches and runtime use an intermediary naming scheme.
     */
    public static final String INTERMEDIARY_TO_NAMED_MAPPING = "intermediaryToNamedMapping";
    /**
     * Same as {@link #INTERMEDIARY_TO_NAMED_MAPPING}, but in CSV format which is used at runtime to provide
     * mapping services for reflection by Forge.
     */
    public static final String CSV_MAPPING = "csvMapping";
    /**
     * Only available if the process defines a step to strip the non-class-files out of the client jar file.
     * This result is the zip file containing all of those stripped resources (any non .class file).
     */
    public static final String CLIENT_RESOURCES = "clientResources";
    /**
     * Only available if the process defines a step to strip the non-class-files out of the server jar file.
     * This result is the zip file containing all of those stripped resources (any non .class file).
     */
    public static final String SERVER_RESOURCES = "serverResources";
    /**
     * Only available if the process defines a step to strip the non-class-files out of the merged client/server jar file.
     * This result is the zip file containing all of those stripped resources (any non .class file).
     */
    public static final String GAME_RESOURCES = "gameResources";

    private ResultIds() {
    }
}
