package net.neoforged.neoform.runtime.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

final class NfrtCommand {
    private static final String GAME_SOURCES_RESULT = "gameSources";

    private final List<String> command;
    private final Path workingDirectory;
    private final Path testDirectory;
    private final Duration timeout;
    private final Map<String, Path> results;

    private NfrtCommand(List<String> command,
                        Path workingDirectory,
                        Path testDirectory,
                        Duration timeout,
                        Map<String, Path> results) {
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.testDirectory = testDirectory;
        this.timeout = timeout;
        this.results = results;
    }

    static Builder builder(Path testDirectory) {
        return new Builder(testDirectory);
    }

    void executeSuccessfully() throws IOException, InterruptedException {
        execute().assertSuccess();
    }

    String readResult(String resultId, String entryName) throws IOException {
        var result = results.get(resultId);
        if (result == null) {
            throw new IllegalArgumentException("Unknown result " + resultId + ". Available: " + results.keySet());
        }

        try (var zip = new ZipFile(result.toFile())) {
            var entry = zip.getEntry(entryName);
            assertThat(entry).as("Entry %s in result %s", entryName, resultId).isNotNull();
            try (var stream = zip.getInputStream(entry)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private Result execute() throws IOException, InterruptedException {
        var consoleLog = Files.createTempFile(testDirectory, "nfrt-console-", ".log");
        var process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(consoleLog.toFile())
                .start();

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
                fail("NFRT timed out after " + timeout.toSeconds()
                     + " seconds. Output:\n" + Files.readString(consoleLog));
            }
            return new Result(process.exitValue(), Files.readString(consoleLog));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private record Result(int exitCode, String output) {
        void assertSuccess() {
            assertThat(exitCode).as("NFRT output:%n%s", output).isZero();
        }
    }

    static final class Builder {
        private final Path testDirectory;
        private final Map<String, String> sources = new LinkedHashMap<>();
        private final List<InputFile> accessTransformers = new ArrayList<>();
        private final List<InputFile> validatedAccessTransformers = new ArrayList<>();
        private Duration timeout = Duration.ofMinutes(1);

        private Builder(Path testDirectory) {
            this.testDirectory = testDirectory.toAbsolutePath();
        }

        Builder source(String path, String content) {
            sources.put(path, content);
            return this;
        }

        Builder accessTransformer(String relativePath, String content) {
            return accessTransformer(Path.of(relativePath), content);
        }

        Builder accessTransformer(Path path, String content) {
            accessTransformers.add(new InputFile(path, content));
            return this;
        }

        Builder validatedAccessTransformer(String relativePath, String content) {
            return validatedAccessTransformer(Path.of(relativePath), content);
        }

        Builder validatedAccessTransformer(Path path, String content) {
            validatedAccessTransformers.add(new InputFile(path, content));
            return this;
        }

        Builder timeout(Duration timeout) {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        NfrtCommand build() throws IOException {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one source must be added");
            }

            Files.createDirectories(testDirectory);
            var workingDirectory = testDirectory.resolve("launch");
            Files.createDirectories(workingDirectory);

            var sourcesArchive = testDirectory.resolve("sources.zip");
            writeZip(sourcesArchive, sources);

            var launcherDirectory = createLauncherDirectory(testDirectory, sourcesArchive);
            var javaSourceTransformerCoordinate = requiredProperty(
                    "nfrt.test.java-source-transformer-coordinate"
            );
            var neoForm = createNeoForm(testDirectory, javaSourceTransformerCoordinate);
            var artifactManifest = createArtifactManifest(testDirectory, javaSourceTransformerCoordinate);

            var regularAtPaths = writeInputFiles(testDirectory, workingDirectory, accessTransformers);
            var validatedAtPaths = writeInputFiles(testDirectory, workingDirectory, validatedAccessTransformers);
            var gameSources = testDirectory.resolve("result.zip");

            var command = new ArrayList<String>();
            command.add(ProcessHandle.current().info().command().orElseThrow());
            command.add("-jar");
            command.add(requiredPathProperty("nfrt.test.executable-jar").toString());
            command.add("--home-dir=" + testDirectory.resolve("home"));
            command.add("--work-dir=" + testDirectory.resolve("work"));
            command.add("--launcher-dir=" + launcherDirectory);
            command.add("--artifact-manifest=" + artifactManifest);
            command.add("--no-color");
            command.add("--no-emojis");
            command.add("run");
            command.add("--disable-cache");
            command.add("--disable-cache-maintenance");
            command.add("--neoform=" + neoForm);
            for (var path : regularAtPaths) {
                command.add("--access-transformer=" + path);
            }
            for (var path : validatedAtPaths) {
                command.add("--validated-access-transformer=" + path);
            }
            command.add("--write-result=" + GAME_SOURCES_RESULT + ":" + gameSources);

            return new NfrtCommand(
                    List.copyOf(command),
                    workingDirectory,
                    testDirectory,
                    timeout,
                    Map.of(GAME_SOURCES_RESULT, gameSources)
            );
        }

        private static Path createLauncherDirectory(Path testDirectory, Path sourcesArchive) throws IOException {
            var launcherDirectory = testDirectory.resolve("launcher");
            var versionDirectory = launcherDirectory.resolve("versions/test");
            Files.createDirectories(versionDirectory);
            Files.writeString(versionDirectory.resolve("test.json"), """
                    {
                      "id": "test",
                      "downloads": {
                        "client": {
                          "sha1": "%s",
                          "size": %d,
                          "url": "%s"
                        }
                      },
                      "libraries": []
                    }
                    """.formatted(sha1(sourcesArchive), Files.size(sourcesArchive), sourcesArchive.toUri()));
            return launcherDirectory;
        }

        private static Path createNeoForm(Path testDirectory,
                                          String javaSourceTransformerCoordinate) throws IOException {
            var neoForm = testDirectory.resolve("neoform.zip");
            writeZip(neoForm, Map.of("config.json", """
                    {
                      "spec": 1,
                      "version": "test",
                      "official": true,
                      "java_target": 21,
                      "encoding": "UTF-8",
                      "data": {},
                      "steps": {
                        "joined": [
                          { "type": "downloadJson" },
                          { "type": "downloadClient" },
                          { "type": "copySources", "name": "decompile", "input": "{downloadClientOutput}" },
                          { "type": "copySources", "name": "patch", "input": "{decompileOutput}" }
                        ]
                      },
                      "functions": {
                        "copySources": {
                          "version": "%s",
                          "args": ["--in-format", "ARCHIVE", "--out-format", "ARCHIVE", "{input}", "{output}"]
                        }
                      },
                      "libraries": {
                        "joined": []
                      }
                    }
                    """.formatted(javaSourceTransformerCoordinate)));
            return neoForm;
        }

        private static Path createArtifactManifest(Path testDirectory,
                                                   String javaSourceTransformerCoordinate) throws IOException {
            var artifactManifest = testDirectory.resolve("artifact-manifest.properties");
            var artifacts = new Properties();
            artifacts.setProperty(
                    javaSourceTransformerCoordinate,
                    requiredPathProperty("nfrt.test.java-source-transformer-jar").toString()
            );
            try (var output = Files.newOutputStream(artifactManifest)) {
                artifacts.store(output, null);
            }
            return artifactManifest;
        }

        private static List<Path> writeInputFiles(Path testDirectory,
                                                  Path workingDirectory,
                                                  List<InputFile> inputs) throws IOException {
            var paths = new ArrayList<Path>(inputs.size());
            for (var input : inputs) {
                var path = input.path().normalize();
                Path output;
                if (path.isAbsolute()) {
                    if (!path.startsWith(testDirectory)) {
                        throw new IllegalArgumentException(
                                "Absolute input path must stay within the test directory: " + path
                        );
                    }
                    output = path;
                } else if (path.startsWith("..")) {
                    throw new IllegalArgumentException(
                            "Relative input path must stay within the working directory: " + path
                    );
                } else {
                    output = workingDirectory.resolve(path);
                }
                Files.createDirectories(output.getParent());
                Files.writeString(output, input.content());
                paths.add(path);
            }
            return paths;
        }

        private static String sha1(Path input) throws IOException {
            try {
                var digest = MessageDigest.getInstance("SHA-1");
                return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(input)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-1 is not available", e);
            }
        }

        private static void writeZip(Path output, Map<String, String> entries) throws IOException {
            try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
                for (var entry : new TreeMap<>(entries).entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }

        private static String requiredProperty(String name) {
            return Objects.requireNonNull(System.getProperty(name), "Missing system property " + name);
        }

        private static Path requiredPathProperty(String name) {
            return Path.of(requiredProperty(name));
        }

        private record InputFile(Path path, String content) {
        }
    }
}
