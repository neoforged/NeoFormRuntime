package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.batch.ClasspathMultiReleaseJar;
import org.eclipse.jdt.internal.compiler.batch.ClasspathSourceJar;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.compiler.env.AccessRule;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

/**
 * Uses Eclipse Compiler for Java to recompile the sources.
 */
public class RecompileSourcesActionWithECJ extends RecompileSourcesAction {
    // Special marker for compilation units that arise from jars on the source path
    // whose compilation result should be discarded.
    private static final String DEV_NULL_DESTINATION = "/dev/null";

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var sources = environment.getRequiredInputPath("sources");
        var javaRelease = String.valueOf(getTargetJavaVersion());

        // Merge the original Minecraft classpath with the libs required by additional patches that we made
        var classpathPaths = getEffectiveClasspath(environment);
        var sourcepathPaths = getEffectiveSourcepath(environment);

        var classpaths = new ArrayList<FileSystem.Classpath>();
        Util.collectRunningVMBootclasspath(classpaths);
        for (Path sourcepathPath : sourcepathPaths) {
            classpaths.add(new ClasspathSourceJar(
                    sourcepathPath.toFile(),
                    true,
                    null,
                    "UTF-8",
                    DEV_NULL_DESTINATION
            ));
        }
        for (var library : classpathPaths) {
            classpaths.add(new ClasspathMultiReleaseJar(
                    library.toFile(),
                    true,
                    new AccessRuleSet(new AccessRule[0], AccessRestriction.COMMAND_LINE, library.getFileName().toString()),
                    "none",
                    javaRelease
            ));
        }

        var nameEnvironment = new ECJFilesystem(
                classpaths.toArray(FileSystem.Classpath[]::new),
                new String[0],
                false
        );
        var policy = DefaultErrorHandlingPolicies.exitOnFirstError();

        var options = new CompilerOptions(Map.of(
                CompilerOptions.OPTION_Source, javaRelease,
                CompilerOptions.OPTION_Compliance, javaRelease,
                CompilerOptions.OPTION_TargetPlatform, javaRelease,
                CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE,
                CompilerOptions.OPTION_ReportDeprecationInDeprecatedCode, CompilerOptions.DISABLED,
                CompilerOptions.OPTION_ReportDeprecationWhenOverridingDeprecatedMethod, CompilerOptions.DISABLED,
                // Replicates -g javac option
                CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE,
                CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE,
                CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE
        ));
        // These are set by the ECJ batch compiler too
        options.performMethodsFullRecovery = false;
        options.performStatementsRecovery = false;
        options.processAnnotations = false;
        options.suppressWarnings = true;

        var compilerRef = new Compiler[1];

        var requestor = new ICompilerRequestor() {
            // Collect in-memory for now
            final Map<String, byte[]> classFileContent = new ConcurrentHashMap<>();
            AtomicLong totalSize = new AtomicLong();

            @Override
            public void acceptResult(CompilationResult result) {
                if (result.hasErrors()) {
                    var filename = new String(result.compilationUnit.getFileName());
                    System.err.println("ERRORS FOUND in " + filename);
                    for (var error : result.getErrors()) {
                        LOG.println("ERROR: " + error);
                    }
                    throw new RuntimeException("Compilation failed. Errors found in " + filename);
                }

                // We are looking specifically for the marker
                //noinspection StringEquality
                if (result.getCompilationUnit().getDestinationPath() == DEV_NULL_DESTINATION) {
                    return;
                }

                for (var classFile : result.getClassFiles()) {
                    var bytes = classFile.getBytes();
                    classFileContent.put(new String(classFile.fileName()) + ".class", bytes);
                    totalSize.addAndGet(bytes.length);
                }
                compilerRef[0].lookupEnvironment.releaseClassFiles(result.getClassFiles());
            }
        };
        var problemFactory = new DefaultProblemFactory(Locale.ROOT);
        var compiler = new Compiler(nameEnvironment, policy, options, requestor, problemFactory);
        compilerRef[0] = compiler;
        compiler.useSingleThread = false; // Multi-thread

        Map<String, byte[]> nonSourceContent = new HashMap<>();

        // Slurp all sources into memory in parallel
        ICompilationUnit[] compilationUnits;
        try (var sourcesZip = new ZipFile(sources.toFile()); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<ICompilationUnit>>();
            var entries = sourcesZip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    if (entry.getName().endsWith(".java") && !entry.getName().equals("package-info-template.java")) {
                        futures.add(executor.submit(() -> {
                            // TODO This is copy heavy and should be optimized
                            try (var in = sourcesZip.getInputStream(entry)) {
                                var contents = new String(in.readAllBytes(), StandardCharsets.UTF_8).toCharArray();
                                return new CompilationUnit(
                                        contents,
                                        entry.getName().replace('\\', '/'),
                                        "UTF-8"
                                );
                            }
                        }));
                    } else {
                        try (var in = sourcesZip.getInputStream(entry)) {
                            nonSourceContent.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }
            }
            compilationUnits = new ICompilationUnit[futures.size()];
            for (int i = 0; i < futures.size(); i++) {
                var future = futures.get(i);
                try {
                    compilationUnits[i] = future.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException runtimeCause) {
                        throw runtimeCause;
                    }
                    throw new RuntimeException(e.getCause());
                }
            }
        }

        LOG.println("Compiling " + compilationUnits.length + " source files...");
        compiler.compile(compilationUnits);
        LOG.println("Wrote " + requestor.classFileContent.size() + " class files (" + requestor.totalSize.get() + " bytes total)");

        var outputPath = environment.getOutputPath("output");
        try (var fileOut = Files.newOutputStream(outputPath); var jos = new JarOutputStream(fileOut)) {
            var keys = new ArrayList<>(requestor.classFileContent.keySet());
            keys.sort(Comparator.naturalOrder());
            for (var key : keys) {
                var content = requestor.classFileContent.get(key);
                var entry = new JarEntry(key);
                jos.putNextEntry(entry);
                jos.write(content);
                jos.closeEntry();
            }

            // Copy over non-source files as well
            for (var entry : nonSourceContent.entrySet()) {
                var jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
            LOG.println("Copied " + nonSourceContent.size() + " resource files");
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.add("compiler type", "eclipse");
    }

    static class ECJFilesystem extends FileSystem {
        protected ECJFilesystem(Classpath[] paths, String[] initialFileNames, boolean annotationsFromClasspath) {
            super(paths, initialFileNames, annotationsFromClasspath);
        }
    }
}
