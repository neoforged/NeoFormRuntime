package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ApplySourceAccessTransformersAction extends ExternalJavaToolAction {
    private static final MavenCoordinate JST_TOOL_COORDINATE = MavenCoordinate.parse("net.neoforged.jst:jst-cli-bundle:1.0.35");
    private final String accessTransformersData;
    private List<Path> additionalAccessTransformers = new ArrayList<>();

    public ApplySourceAccessTransformersAction(String accessTransformersData) {
        super(JST_TOOL_COORDINATE);
        this.accessTransformersData = accessTransformersData;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var args = new ArrayList<String>();
        Collections.addAll(args,
                "--libraries-list", "{libraries}",
                "--in-format", "ARCHIVE",
                "--out-format", "ARCHIVE",
                "--enable-accesstransformers"
        );

        var accessTransformers = environment.extractData(accessTransformersData);

        try (var stream = Files.walk(accessTransformers)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                args.add("--access-transformer");
                args.add(environment.getWorkspace().relativize(path).toString());
            });
        }

        for (var path : additionalAccessTransformers) {
            args.add("--access-transformer");
            args.add(environment.getWorkspace().relativize(path).toString());
        }

        Collections.addAll(args, "{input}", "{output}");
        setArgs(args);

        super.run(environment);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addPaths("additional access transformers", additionalAccessTransformers);
    }

    public List<Path> getAdditionalAccessTransformers() {
        return additionalAccessTransformers;
    }

    public void setAdditionalAccessTransformers(List<Path> additionalAccessTransformers) {
        this.additionalAccessTransformers = List.copyOf(additionalAccessTransformers);
    }
}
