package net.neoforged.neoform.runtime.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class NfrtFixtureSupport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NfrtFixtureSupport() {
    }

    static Path createLauncherDirectory(Path testDirectory,
                                        String minecraftVersion,
                                        Map<String, Path> downloads,
                                        Map<String, Path> libraries) throws IOException {
        var launcherDirectory = testDirectory.resolve("launcher");
        var versionDirectory = launcherDirectory.resolve("versions").resolve(minecraftVersion);
        Files.createDirectories(versionDirectory);

        var downloadEntries = new LinkedHashMap<String, Object>();
        for (var entry : downloads.entrySet()) {
            var artifact = entry.getValue();
            var download = new LinkedHashMap<String, Object>();
            download.put("sha1", sha1(artifact));
            download.put("size", Files.size(artifact));
            download.put("url", artifact.toUri().toString());
            downloadEntries.put(entry.getKey(), download);
        }

        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("id", minecraftVersion);
        manifest.put("downloads", downloadEntries);
        var libraryEntries = new ArrayList<Map<String, Object>>();
        for (var entry : libraries.entrySet()) {
            var artifact = entry.getValue();
            var download = new LinkedHashMap<String, Object>();
            download.put("sha1", sha1(artifact));
            download.put("size", Files.size(artifact));
            download.put("url", artifact.toUri().toString());
            libraryEntries.add(Map.of(
                    "name", entry.getKey(),
                    "downloads", Map.of("artifact", download)
            ));
        }
        manifest.put("libraries", libraryEntries);
        Files.writeString(versionDirectory.resolve(minecraftVersion + ".json"), toJson(manifest));
        return launcherDirectory;
    }

    static Path compileSource(Path testDirectory, String sourcePath, String source, int javaVersion) throws IOException {
        return compileSources(
                testDirectory,
                Map.of(sourcePath, source.getBytes(StandardCharsets.UTF_8)),
                javaVersion
        );
    }

    static Path compileSources(Path testDirectory, Map<String, byte[]> sources, int javaVersion) throws IOException {
        var sourceDirectory = testDirectory.resolve("launcher-sources");
        var classesDirectory = testDirectory.resolve("launcher-classes");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classesDirectory);

        var sourceFiles = new ArrayList<String>();
        for (var entry : sources.entrySet()) {
            if (!entry.getKey().endsWith(".java")) {
                continue;
            }
            var source = safeResolve(sourceDirectory, entry.getKey());
            Files.createDirectories(source.getParent());
            Files.write(source, entry.getValue());
            sourceFiles.add(source.toString());
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Integration tests require a JDK");
        }
        var output = new ByteArrayOutputStream();
        var compilerArguments = new ArrayList<>(List.of(
                "--release", Integer.toString(javaVersion),
                "-proc:none",
                "-d", classesDirectory.toString()
        ));
        compilerArguments.addAll(sourceFiles);
        var exitCode = compiler.run(null, output, output, compilerArguments.toArray(String[]::new));
        if (exitCode != 0) {
            throw new IOException("Failed to compile launcher fixture sources:\n" + output);
        }

        var entries = new LinkedHashMap<String, byte[]>();
        try (var files = Files.walk(classesDirectory)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                entries.put(classesDirectory.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        for (var entry : sources.entrySet()) {
            if (!entry.getKey().endsWith(".java")) {
                entries.put(entry.getKey(), entry.getValue());
            }
        }

        var clientJar = testDirectory.resolve("client.jar");
        writeZip(clientJar, entries);
        return clientJar;
    }

    static void writeTextZip(Path output, Map<String, String> entries) throws IOException {
        var byteEntries = new LinkedHashMap<String, byte[]>();
        for (var entry : entries.entrySet()) {
            byteEntries.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        writeZip(output, byteEntries);
    }

    static byte[] readZipEntry(Path input, String entryName) throws IOException {
        try (var zip = new ZipFile(input.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Entry " + entryName + " not found in " + input);
            }
            try (var stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    static void writeZip(Path output, Map<String, byte[]> entries) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    static byte[] jsonBytes(Object value) {
        return toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    static String toJson(Object value) {
        return GSON.toJson(value);
    }

    private static Path safeResolve(Path root, String relativePath) {
        var result = root.resolve(relativePath).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("ZIP entry escapes its root: " + relativePath);
        }
        return result;
    }

    private static String sha1(Path input) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(input)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
