package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import net.neoforged.problems.FileProblemReporter;
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
import java.util.zip.ZipOutputStream;

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
    protected static final Logger LOG = Logger.create();

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
     * <p>
     * A description of the format can be found in the <a href="https://docs.neoforged.net/docs/advanced/accesstransformers">NeoForge documentation</a>.
     */
    private List<Path> additionalAccessTransformers = new ArrayList<>();

    /**
     * Same as {@link #additionalAccessTransformers}, but entries in this list will fail the build if
     * any errors for them are reported during application.
     */
    private List<Path> validatedAccessTransformers = new ArrayList<>();

    /**
     * Additional paths to interface injection data files.
     */
    private List<Path> injectedInterfaces = new ArrayList<>();

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

        var problemsReport = environment.getWorkspace().resolve("problems.json");

        Collections.addAll(args,
                "--problems-report", problemsReport.toAbsolutePath().toString(),
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

        if (!injectedInterfaces.isEmpty()) {
            args.add("--enable-interface-injection");
            for (var path : injectedInterfaces) {
                args.add("--interface-injection-data");
                args.add(environment.getPathArgument(path.toAbsolutePath()));
            }
            args.add("--interface-injection-stubs");
            args.add("{stubs}");
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

        try {
            super.run(environment);
        } catch (Exception e) {
            // Pass through *all* problems if possible
            try {
                environment.getProblemReporter().tryMergeFromFile(problemsReport);
            } catch (IOException ex) {
                LOG.warn("Failed to pass-through problem report from " + problemsReport + ": " + e);
            }

            throw e;
        }

        // Pass through any relevant problems to the outer problem context
        if (Files.exists(problemsReport)) {
            var problems = FileProblemReporter.loadRecords(problemsReport);
            for (var problem : problems) {
                if (problem.location() == null || validatedAccessTransformers.contains(problem.location().file())) {
                    environment.getProblemReporter().report(problem);
                }
            }

            // Now collect problems for any validated ATs and fail if there are any
            var problemList = problems.stream()
                    .filter(problem -> problem.location() != null && validatedAccessTransformers.contains(problem.location().file()))
                    .map(p -> " - " + problems)
                    .collect(Collectors.joining("\n"));
            if (!problemList.isEmpty()) {
                throw new RuntimeException("Access transformers failed validation:\n" + problemList);
            }
        }

        // When no interface data is given, we still have to create an empty stubs zip to satisfy
        // the output
        if (injectedInterfaces.isEmpty()) {
            var stubsPath = environment.getOutputPath("stubs");
            try {
                new ZipOutputStream(Files.newOutputStream(stubsPath)).close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create empty stub zip at " + stubsPath, e);
            }
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.addStrings("access transformers data ids", accessTransformersData);
        ck.addPaths("additional access transformers", additionalAccessTransformers);
        ck.addPaths("injected interfaces", injectedInterfaces);
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

    public List<Path> getValidatedAccessTransformers() {
        return validatedAccessTransformers;
    }

    public void setValidatedAccessTransformers(List<Path> validatedAccessTransformers) {
        this.validatedAccessTransformers = validatedAccessTransformers;
    }

    public void setInjectedInterfaces(List<Path> injectedInterfaces) {
        this.injectedInterfaces = List.copyOf(injectedInterfaces);
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
