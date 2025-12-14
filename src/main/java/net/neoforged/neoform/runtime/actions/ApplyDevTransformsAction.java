package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

public class ApplyDevTransformsAction extends ExternalJavaToolAction {
    /**
     * Names of {@linkplain NeoFormEngine#addDataSource(String, ZipFile, String) data sources} containing
     * access transformers to apply.
     */
    private List<String> accessTransformersData = new ArrayList<>();
    /**
     * Paths to access transformers that will be applied.
     */
    private List<Path> additionalAccessTransformers = new ArrayList<>();

    /**
     * Paths to interface injection data files.
     */
    private List<Path> injectedInterfaces = new ArrayList<>();

    public ApplyDevTransformsAction() {
        super(ToolCoordinate.INSTALLER_TOOLS);
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var args = new ArrayList<String>();

        Collections.addAll(args,
                "--task", "PROCESS_MINECRAFT_JAR",
                "--input", "{input}",
                "--output", "{output}",
                "--no-mod-manifest");

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
            args.add(environment.getPathArgument(path.toAbsolutePath()));
        }

        for (var path : injectedInterfaces) {
            args.add("--interface-injection-data");
            args.add(environment.getPathArgument(path.toAbsolutePath()));
        }

        setArgs(args);
        super.run(environment);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("access transformers data ids", accessTransformersData);
        ck.addPaths("additional access transformers", additionalAccessTransformers);
        ck.addPaths("injected interfaces", injectedInterfaces);
    }

    public void setAccessTransformersData(List<String> accessTransformersData) {
        this.accessTransformersData = accessTransformersData;
    }

    public void setAdditionalAccessTransformers(List<Path> additionalAccessTransformers) {
        this.additionalAccessTransformers = additionalAccessTransformers;
    }

    public void setInjectedInterfaces(List<Path> injectedInterfaces) {
        this.injectedInterfaces = List.copyOf(injectedInterfaces);
    }
}
