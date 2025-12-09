package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ApplyDevTransformsAction extends ExternalJavaToolAction {
    /**
     * Paths to access transformers that will be applied.
     */
    private List<Path> accessTransformers = new ArrayList<>();

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

        for (var path : accessTransformers) {
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
        ck.addPaths("access transformers", accessTransformers);
        ck.addPaths("injected interfaces", injectedInterfaces);
    }

    public void setAccessTransformers(List<Path> accessTransformers) {
        this.accessTransformers = accessTransformers;
    }

    public void setInjectedInterfaces(List<Path> injectedInterfaces) {
        this.injectedInterfaces = List.copyOf(injectedInterfaces);
    }
}
