package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.cli.ProcessingEnvironment;

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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecompileSourcesAction extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var sources = environment.getRequiredInputPath("sources");
        var librariesFile = environment.getRequiredInputPath("libraries");
        var libraries = Files.readAllLines(librariesFile)
                .stream()
                .map(line -> line.replaceFirst("^-e=", ""))
                .map(Paths::get)
                .toList();

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
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, libraries);

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
