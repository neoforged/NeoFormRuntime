package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.DataSource;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

public class ApplyDevTransformsAction extends ExternalJavaToolAction {
    /**
     * Data sources containing access transformers to apply, keyed by the id registered with
     * {@linkplain NeoFormEngine#addDataSource(String, java.util.zip.ZipFile, String)}.
     */
    private final SequencedMap<String, DataSource> accessTransformersData = new LinkedHashMap<>();

    /**
     * Paths to access transformers that will be applied.
     */
    private List<Path> additionalAccessTransformers = List.of();

    /**
     * Paths to interface injection data files.
     */
    private List<Path> injectedInterfaces = List.of();

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

        for (var dataId : accessTransformersData.sequencedKeySet()) {
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
        for (var entry : accessTransformersData.entrySet()) {
            ck.add("access transformers data[" + entry.getKey() + "]", entry.getValue().cacheKey());
        }
        ck.addPaths("additional access transformers", additionalAccessTransformers);
        ck.addPaths("injected interfaces", injectedInterfaces);
    }

    public void addAccessTransformersData(String id, DataSource dataSource) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dataSource, "dataSource");
        if (accessTransformersData.put(id, dataSource) != null) {
            throw new IllegalArgumentException("Access transformers data source " + id + " was registered twice.");
        }
    }

    public List<String> getAccessTransformersData() {
        return List.copyOf(accessTransformersData.sequencedKeySet());
    }

    public void setAdditionalAccessTransformers(List<Path> additionalAccessTransformers) {
        this.additionalAccessTransformers = List.copyOf(additionalAccessTransformers);
    }

    public List<Path> getAdditionalAccessTransformers() {
        return additionalAccessTransformers;
    }

    public void setInjectedInterfaces(List<Path> injectedInterfaces) {
        this.injectedInterfaces = List.copyOf(injectedInterfaces);
    }

    public List<Path> getInjectedInterfaces() {
        return injectedInterfaces;
    }
}
