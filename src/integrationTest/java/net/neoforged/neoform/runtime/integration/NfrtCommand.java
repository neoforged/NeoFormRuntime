package net.neoforged.neoform.runtime.integration;

import net.neoforged.neoform.runtime.cli.ResultIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

final class NfrtCommand {
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

    static Builder builder(Path testDirectory, NfrtFixture fixture) {
        return new Builder(testDirectory, fixture);
    }

    String executeSuccessfully() throws IOException, InterruptedException {
        var result = execute();
        result.assertSuccess();
        return result.output();
    }

    String executeExpectingFailure() throws IOException, InterruptedException {
        var result = execute();
        result.assertFailure();
        return result.output();
    }

    Path resultPath(String resultId) {
        var result = results.get(resultId);
        if (result == null) {
            throw new IllegalArgumentException("Unknown result " + resultId + ". Available: " + results.keySet());
        }
        return result;
    }

    Set<String> resultEntries(String resultId) throws IOException {
        try (var zip = new ZipFile(resultPath(resultId).toFile())) {
            var entries = new LinkedHashSet<String>();
            zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> entries.add(entry.getName()));
            return entries;
        }
    }

    String readResult(String resultId, String entryName) throws IOException {
        return new String(readResultBytes(resultId, entryName), StandardCharsets.UTF_8)
                // When running tests on Windows,
                // reading from the file system will give CRLF endings,
                // but the string literals for the expected result have LF endings
                .replace(System.lineSeparator(), "\n");
    }

    byte[] readResultBytes(String resultId, String entryName) throws IOException {
        try (var zip = new ZipFile(resultPath(resultId).toFile())) {
            var entry = zip.getEntry(entryName);
            assertThat(entry).as("Entry %s in result %s", entryName, resultId).isNotNull();
            try (var stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
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

        void assertFailure() {
            assertThat(exitCode).as("NFRT output:%n%s", output).isNotZero();
        }
    }

    static final class Builder {
        private final Path testDirectory;
        private final NfrtFixture fixture;
        private final List<InputFile> accessTransformers = new ArrayList<>();
        private final List<InputFile> validatedAccessTransformers = new ArrayList<>();
        private final List<String> arguments = new ArrayList<>();
        private final Map<String, Path> artifacts = new LinkedHashMap<>();
        private final Set<String> requestedResults = new LinkedHashSet<>();
        private Duration timeout = Duration.ofMinutes(2);
        private InputFile interfaceInjectionData;
        private InputFile parchmentData;
        private boolean cacheEnabled;

        private Builder(Path testDirectory, NfrtFixture fixture) {
            this.testDirectory = testDirectory.toAbsolutePath();
            this.fixture = Objects.requireNonNull(fixture);
        }

        Builder accessTransformer(String content) {
            var path = Path.of("config/access-transformer-" + (accessTransformers.size() + 1) + ".cfg");
            return accessTransformer(path, content);
        }

        Builder accessTransformer(Path path, String content) {
            accessTransformers.add(new InputFile(path, content));
            return this;
        }

        Builder validatedAccessTransformer(String content) {
            var path = Path.of(
                    "config/validated-access-transformer-" + (validatedAccessTransformers.size() + 1) + ".cfg"
            );
            return validatedAccessTransformer(path, content);
        }

        Builder validatedAccessTransformer(Path path, String content) {
            validatedAccessTransformers.add(new InputFile(path, content));
            return this;
        }

        Builder interfaceInjectionData(Map<String, ?> content) {
            interfaceInjectionData = new InputFile(
                    Path.of("config/interfaces.json"),
                    NfrtFixtureSupport.toJson(content)
            );
            return this;
        }

        Builder parchmentData(ParchmentData content) {
            parchmentData = new InputFile(
                    Path.of("config/parchment.json"),
                    Objects.requireNonNull(content).content()
            );
            return this;
        }

        Builder argument(String argument) {
            arguments.add(argument);
            return this;
        }

        Builder artifact(String coordinate, Path artifact) {
            artifacts.put(Objects.requireNonNull(coordinate), Objects.requireNonNull(artifact));
            return this;
        }

        Builder enableCache() {
            cacheEnabled = true;
            return this;
        }

        Builder result(String resultId) {
            requestedResults.add(resultId);
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
            Files.createDirectories(testDirectory);
            var workingDirectory = testDirectory.resolve("launch");
            Files.createDirectories(workingDirectory);

            var javaSourceTransformerCoordinate = requiredProperty(
                    "nfrt.test.java-source-transformer-coordinate"
            );
            var materializedFixture = fixture.materialize(new NfrtFixture.Context(
                    testDirectory,
                    javaSourceTransformerCoordinate
            ));
            var artifactManifest = createArtifactManifest(testDirectory, artifacts);

            var regularAtPaths = writeInputFiles(testDirectory, workingDirectory, accessTransformers);
            var validatedAtPaths = writeInputFiles(testDirectory, workingDirectory, validatedAccessTransformers);
            var interfaceInjectionPath = interfaceInjectionData == null
                    ? null
                    : writeInputFile(testDirectory, workingDirectory, interfaceInjectionData);
            var parchmentPath = parchmentData == null
                    ? null
                    : writeInputFile(testDirectory, workingDirectory, parchmentData);

            var command = new ArrayList<String>();
            command.add(ProcessHandle.current().info().command().orElseThrow());
            command.add("-jar");
            command.add(requiredPathProperty("nfrt.test.executable-jar").toString());
            command.add("--home-dir=" + testDirectory.resolve("home"));
            command.add("--work-dir=" + testDirectory.resolve("work"));
            command.add("--launcher-dir=" + materializedFixture.launcherDirectory());
            command.add("--artifact-manifest=" + artifactManifest);
            command.add("--repository=" + testDirectory.resolve("empty-repository").toUri());
            command.add("--no-warn-on-artifact-manifest-miss");
            command.add("--no-color");
            command.add("--no-emojis");
            command.add("run");
            if (!cacheEnabled) {
                command.add("--disable-cache");
            }
            command.add("--disable-cache-maintenance");
            command.add(materializedFixture.sourceArtifactArgument());
            command.addAll(arguments);
            for (var path : regularAtPaths) {
                command.add("--access-transformer=" + path);
            }
            for (var path : validatedAtPaths) {
                command.add("--validated-access-transformer=" + path);
            }
            if (interfaceInjectionPath != null) {
                command.add("--interface-injection-data=" + interfaceInjectionPath);
            }
            if (parchmentPath != null) {
                command.add("--parchment-data=" + parchmentPath);
            }

            var resultIds = requestedResults.isEmpty() ? Set.of(ResultIds.GAME_SOURCES) : requestedResults;
            var results = new LinkedHashMap<String, Path>();
            for (var resultId : resultIds) {
                var output = testDirectory.resolve("results").resolve(resultId + ".zip");
                Files.createDirectories(output.getParent());
                command.add("--write-result=" + resultId + ":" + output);
                results.put(resultId, output);
            }

            return new NfrtCommand(
                    List.copyOf(command),
                    workingDirectory,
                    testDirectory,
                    timeout,
                    Map.copyOf(results)
            );
        }

        private static Path createArtifactManifest(Path testDirectory,
                                                   Map<String, Path> additionalArtifacts) throws IOException {
            var artifactManifest = testDirectory.resolve("artifact-manifest.properties");
            var artifacts = new Properties();
            addTool(artifacts, "java-source-transformer");
            addTool(artifacts, "diff-patch");
            addTool(artifacts, "installer-tools");
            addTool(artifacts, "side-annotation-stripper");
            additionalArtifacts.forEach((coordinate, artifact) ->
                    artifacts.setProperty(coordinate, artifact.toAbsolutePath().toString())
            );
            try (var output = Files.newOutputStream(artifactManifest)) {
                artifacts.store(output, null);
            }
            return artifactManifest;
        }

        private static void addTool(Properties artifacts, String propertyPrefix) {
            artifacts.setProperty(
                    requiredProperty("nfrt.test." + propertyPrefix + "-coordinate"),
                    requiredPathProperty("nfrt.test." + propertyPrefix + "-jar").toString()
            );
        }

        private static List<Path> writeInputFiles(Path testDirectory,
                                                  Path workingDirectory,
                                                  List<InputFile> inputs) throws IOException {
            var paths = new ArrayList<Path>(inputs.size());
            for (var input : inputs) {
                paths.add(writeInputFile(testDirectory, workingDirectory, input));
            }
            return paths;
        }

        private static Path writeInputFile(Path testDirectory,
                                           Path workingDirectory,
                                           InputFile input) throws IOException {
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
            return path;
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
