package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

public class ApplySourceAccessTransformersAction extends ExternalJavaToolAction {
    private static final MavenCoordinate JST_TOOL_COORDINATE = MavenCoordinate.parse("net.neoforged.jst:jst-cli-bundle:1.0.37");

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

    public ApplySourceAccessTransformersAction() {
        super(JST_TOOL_COORDINATE);
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
                        args.add(environment.getWorkspace().relativize(path).toString());
                    });
                }
            }

            for (var path : additionalAccessTransformers) {
                args.add("--access-transformer");
                args.add(environment.getWorkspace().relativize(path).toString());
            }
        }

        if (parchmentData != null) {
            args.add("--enable-parchment");
            args.add("--parchment-mappings=" + environment.getPathArgument(parchmentData.toAbsolutePath()));
        }

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
}
