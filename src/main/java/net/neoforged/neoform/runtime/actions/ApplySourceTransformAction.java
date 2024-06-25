package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/**
 * Uses <a href="https://github.com/neoforged/JavaSourceTransformer">Java Source Transformer</a> to apply
 * transforms to Java Source code.
 * <p>
 * Transforms such as:
 * <ul>
 *     <li>Widening the access level of methods, fields and classes using access transformers</li>
 *     <li>Applying method parameter names and Javadoc using data from the parchment project.</li>
 * </ul>
 */
public class ApplySourceTransformAction extends ExternalJavaToolAction {
    /**
     * Additional libraries to be added to the classpath for parsing the sources.
     * Minecraft libraries are pulled in automatically from the same source used by the
     * decompiler.
     */
    private final ExtensibleClasspath parserClasspath = new ExtensibleClasspath();

    /**
     * names of {@linkplain net.neoforged.neoform.runtime.engine.NeoFormEngine#addDataSource(String, ZipFile, String) data sources} containing
     * access transformers to apply.
     */
    private List<String> accessTransformersData = new ArrayList<>();

    /**
     * Additional paths to access transformers.
     */
    private List<Path> additionalAccessTransformers = new ArrayList<>();

    /**
     * Path to a Parchment data archive.
     */
    @Nullable
    private Path parchmentData;

    /**
     * Additional options to pass to the source transformer tool.
     */
    private List<String> additionalArguments = new ArrayList<>();

    public ApplySourceTransformAction() {
        super(ToolCoordinate.JAVA_SOURCE_TRANSFORMER);
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var args = new ArrayList<String>();
        Collections.addAll(args,
                "--libraries-list", "{libraries}",
                "--in-format", "ARCHIVE",
                "--out-format", "ARCHIVE"
        );

        if (!accessTransformersData.isEmpty() || !additionalAccessTransformers.isEmpty()) {
            args.add("--enable-accesstransformers");

            for (var dataId : accessTransformersData) {
                var accessTransformers = environment.extractData(dataId);

                try (var stream = Files.walk(accessTransformers)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        args.add("--access-transformer");
                        args.add(environment.getPathArgument(path));
                    });
                }
            }

            for (var path : additionalAccessTransformers) {
                args.add("--access-transformer");
                args.add(environment.getPathArgument(path));
            }
        }

        if (parchmentData != null) {
            args.add("--enable-parchment");
            args.add("--parchment-mappings=" + environment.getPathArgument(parchmentData.toAbsolutePath()));
        }

        if (!parserClasspath.isEmpty()) {
            var classpath = environment.getArtifactManager().resolveClasspath(parserClasspath.getEffectiveClasspath());
            args.add("--classpath");
            args.add(classpath.stream()
                    .map(environment::getPathArgument)
                    .collect(Collectors.joining(File.pathSeparator)));
        }

        args.addAll(additionalArguments);

        Collections.addAll(args, "{input}", "{output}");
        setArgs(args);

        super.run(environment);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("access transformers data ids", accessTransformersData);
        ck.addPaths("additional access transformers", additionalAccessTransformers);
        if (parchmentData != null) {
            ck.addPath("parchment data", parchmentData);
        }
        ck.addStrings("additional arguments", additionalArguments);
        parserClasspath.computeCacheKey("parser classpath", ck);
    }

    public List<String> getAccessTransformersData() {
        return accessTransformersData;
    }

    public void setAccessTransformersData(List<String> accessTransformersData) {
        this.accessTransformersData = List.copyOf(accessTransformersData);
    }

    public List<Path> getAdditionalAccessTransformers() {
        return additionalAccessTransformers;
    }

    public void setAdditionalAccessTransformers(List<Path> additionalAccessTransformers) {
        this.additionalAccessTransformers = List.copyOf(additionalAccessTransformers);
    }

    public @Nullable Path getParchmentData() {
        return parchmentData;
    }

    public void setParchmentData(@Nullable Path parchmentData) {
        this.parchmentData = parchmentData;
    }

    public ExtensibleClasspath getParserClasspath() {
        return parserClasspath;
    }

    public List<String> getAdditionalArguments() {
        return additionalArguments;
    }

    public void setAdditionalArguments(List<String> additionalArguments) {
        this.additionalArguments = additionalArguments;
    }

    public void addArg(String arg) {
        additionalArguments.add(arg);
    }
}
