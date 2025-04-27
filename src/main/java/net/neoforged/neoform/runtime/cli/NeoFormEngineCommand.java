package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.problems.FileProblemReporter;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public abstract class NeoFormEngineCommand implements Callable<Integer> {
    private static final Logger LOG = Logger.create();

    @CommandLine.ParentCommand
    Main commonOptions;

    @CommandLine.Option(names = "--print-graph")
    boolean printGraph;

    @CommandLine.Option(names = "--use-eclipse-compiler")
    boolean useEclipseCompiler;

    /**
     * Overrides the compile-classpath wherever it may be used.
     */
    @CommandLine.Option(names = "--compile-classpath")
    String compileClasspath;

    @CommandLine.Option(names = "--disable-cache")
    boolean disableCache;

    @CommandLine.Option(names = "--analyze-cache-misses")
    boolean analyzeCacheMisses;

    @CommandLine.Option(names = "--disable-cache-maintenance", description = "Skip automatically running cache maintenance from time to time")
    boolean disableCacheMaintenance;

    @CommandLine.Option(names = "--java-home", description = "The home of the JRE to run external tools with. If not specified, the Java running NFRT will be used.")
    @Nullable
    Path javaHome;

    @CommandLine.Option(names = "--java-executable", description = "The path to the Java executable to run external tools with. If not specified, the Java running NFRT will be used.")
    @Nullable
    Path javaExecutable;

    @CommandLine.Option(names = "--problems-report", description = "Writes a JSON problem report to this file containing all problems that ocurred during execution of NFRT")
    @Nullable
    Path problemReport;

    protected abstract void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException;

    @Override
    public final Integer call() throws Exception {
        var start = System.currentTimeMillis();

        var closables = new ArrayList<AutoCloseable>();

        FileProblemReporter problemReporter = null;
        if (problemReport != null) {
            problemReporter = new FileProblemReporter(problemReport);
            closables.add(problemReporter);
        }

        var launcherInstallations = commonOptions.createLauncherInstallations();

        try (var cacheManager = commonOptions.createCacheManager();
             var downloadManager = new DownloadManager()) {
            var lockManager = commonOptions.createLockManager();
            cacheManager.setDisabled(disableCache);
            cacheManager.setAnalyzeMisses(analyzeCacheMisses);

            if (!disableCacheMaintenance) {
                cacheManager.performMaintenance();
                lockManager.performMaintenance();
            }

            var artifactManager = commonOptions.createArtifactManager(cacheManager, downloadManager, lockManager, launcherInstallations);

            var fileHashService = new FileHashService();
            try (var engine = new NeoFormEngine(artifactManager, fileHashService, cacheManager, lockManager)) {
                if (problemReporter != null) {
                    engine.setProblemReporter(problemReporter);
                }

                if (javaExecutable != null) {
                    engine.setJavaExecutable(javaExecutable.toString());
                } else if (javaHome != null) {
                    engine.setJavaHome(javaHome);
                }

                engine.setVerbose(commonOptions.verbose);
                applyBuildOptions(engine);

                runWithNeoFormEngine(engine, closables);
            }
        } finally {
            for (var closable : closables) {
                try {
                    closable.close();
                } catch (Exception e) {
                    System.err.println("Failed to close " + closable + ": " + e);
                }
            }

            var elapsed = System.currentTimeMillis() - start;
            LOG.println(String.format(Locale.ROOT, "Total runtime: %.02fs\n", elapsed / 1000.0));
        }
        return 0;
    }

    private void applyBuildOptions(NeoFormEngine engine) {
        engine.getBuildOptions().setUseEclipseCompiler(useEclipseCompiler);

        if (compileClasspath != null) {
            var compileClasspath = Arrays.stream(this.compileClasspath.split(Pattern.quote(File.pathSeparator)))
                    .map(Paths::get)
                    .map(ClasspathItem::of)
                    .toList();
            engine.getBuildOptions().setOverriddenCompileClasspath(compileClasspath);
        }
    }
}
