package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Uses the current JDKs java compiler interface to recompile the sources.
 */
public class RecompileSourcesActionWithJDK extends RecompileSourcesAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var sources = environment.getRequiredInputPath("sources");
        var classpath = getEffectiveClasspath(environment);
        var sourcepath = getEffectiveSourcepath(environment);

        URI uri = URI.create("jar:" + sources.toUri());
        try (var sourceFs = FileSystems.newFileSystem(uri, Map.of())) {
            var compiler = ToolProvider.getSystemJavaCompiler();

            var sourceRoot = sourceFs.getRootDirectories().iterator().next();
            List<Path> sourcePaths = new ArrayList<>();
            List<Path> nonSourcePaths = new ArrayList<>();
            try (var stream = Files.walk(sourceRoot).filter(Files::isRegularFile)) {
                stream.forEach(path -> {
                    var filename = path.getFileName().toString();
                    if (filename.endsWith(".java") && !filename.equals("package-info-template.java")) {
                        sourcePaths.add(path);
                    } else {
                        nonSourcePaths.add(path);
                    }
                });
            }

            LOG.println(" Compiling " + sourcePaths.size() + " source files");

            var diagnostics = new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> d) {
                    if (!environment.isVerbose() && d.getKind() != Diagnostic.Kind.ERROR) {
                        return; // Ignore anything but errors unless we're in verbose mode
                    }

                    var location = d.getSource() != null ? d.getSource().getName() : "<unknown>";
                    LOG.println(" " + d.getKind() + " Line: " + d.getLineNumber() + ", " + d.getMessage(null) + " in " + location);
                }
            };

            var outputPath = environment.getOutputPath("output");
            try (var outputFs = FileSystems.newFileSystem(URI.create("jar:" + outputPath.toUri()), Map.of("create", true))) {
                var outputRoot = outputFs.getRootDirectories().iterator().next();

                try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Collections.singleton(outputRoot));
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                    fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcepath);

                    var sourceJavaFiles = fileManager.getJavaFileObjectsFromPaths(sourcePaths);
                    var task = compiler.getTask(null, fileManager, diagnostics, getCompilerOptions(), null, sourceJavaFiles);
                    if (!task.call()) {
                        throw new IOException("Compilation failed");
                    }
                }

                // Copy over all non-java files as well
                for (var nonSourcePath : nonSourcePaths) {
                    var relativeDestinationPath = sourceRoot.relativize(nonSourcePath).toString().replace('\\', '/');
                    var destination = outputRoot.resolve(relativeDestinationPath);
                    Files.createDirectories(destination.getParent());
                    Files.copy(nonSourcePath, destination);
                }
                LOG.println("Copied " + nonSourcePaths.size() + " resource files");
            }
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.add("compiler type", "javac");
        ck.addStrings("compiler options", getCompilerOptions());
    }

    private List<String> getCompilerOptions() {
        return List.of(
                "--release",
                String.valueOf(getTargetJavaVersion()),
                "-proc:none", // No annotation processing on Minecraft sources
                "-nowarn", // We have no influence on Minecraft sources, so no warnings
                "-g", // Gradle compiles with debug by default, so we replicate this
                "-XDuseUnsharedTable=true", // Gradle also adds this unconditionally
                "-implicit:none" // Prevents source files from the source-path from being emitted
        );
    }
}
