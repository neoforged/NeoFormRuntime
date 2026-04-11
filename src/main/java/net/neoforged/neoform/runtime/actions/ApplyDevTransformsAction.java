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
    private List<String> accessTransformersData = List.of();
    /**
     * Paths to access transformers that will be applied.
     */
    private List<Path> additionalAccessTransformers = List.of();

    /**
     * Paths to interface injection data files.
     */
    private List<Path> injectedInterfaces = List.of();

    /**
     * Paths to enum extensions to apply.
     */
    private List<Path> enumExtensions = List.of();

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

        for (var path : enumExtensions) {
            args.add("--enum-extensions-data");
            args.add(environment.getPathArgument(path.toAbsolutePath()));
        }

        args.add("--enum-extensions-marker");
        args.add(EnumExtensionDefaults.MARKER_ANNOTATION);

        setArgs(args);
        super.run(environment);
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("access transformers data ids", accessTransformersData);
        ck.addPaths("additional access transformers", additionalAccessTransformers);
        ck.addPaths("injected interfaces", injectedInterfaces);
        ck.addPaths("enum extensions", enumExtensions);
    }

    public void setAccessTransformersData(List<String> accessTransformersData) {
        this.accessTransformersData = List.copyOf(accessTransformersData);
    }

    public List<String> getAccessTransformersData() {
        return accessTransformersData;
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

    public void setEnumExtensions(List<Path> enumExtensions) {
        this.enumExtensions = List.copyOf(enumExtensions);
    }

    public List<Path> getEnumExtensions() {
        return enumExtensions;
    }
}
