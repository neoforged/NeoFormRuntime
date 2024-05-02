package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.engine.ProcessingEnvironment;

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
        var classpath = getLibraries(environment);

        var javaCompilerOptions = new ArrayList<String>();
        javaCompilerOptions.add("-proc:none"); // No annotation processing on Minecraft sources
        javaCompilerOptions.add("-nowarn"); // We have no influence on Minecraft sources, so no warnings

        URI uri = URI.create("jar:" + sources.toUri());
        try (var fs = FileSystems.newFileSystem(uri, Map.of())) {
            var compiler = ToolProvider.getSystemJavaCompiler();

            var root = fs.getRootDirectories().iterator().next();
            List<Path> sourcePaths;
            try (var stream = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))) {
                sourcePaths = stream.toList();
            }

            System.out.println("Compiling " + sourcePaths.size() + " source files");

            var diagnostics = new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> d) {
                    System.out.format("Line: %d, %s in %s\n",
                            d.getLineNumber(), d.getMessage(null),
                            d.getSource() != null ? d.getSource().getName() : "<unknown>");
                }
            };

            var outputPath = environment.getOutputPath("output");
            try (var outputFs = FileSystems.newFileSystem(URI.create("jar:" + outputPath.toUri()), Map.of("create", true))) {
                var outputRoot = outputFs.getRootDirectories().iterator().next();

                try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Collections.singleton(outputRoot));
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);

                    var sourceJavaFiles = fileManager.getJavaFileObjectsFromPaths(sourcePaths);
                    var task = compiler.getTask(null, fileManager, diagnostics, javaCompilerOptions, null, sourceJavaFiles);
                    if (!task.call()) {
                        throw new IOException("Compilation failed");
                    }
                }
            }
        }
    }
}
