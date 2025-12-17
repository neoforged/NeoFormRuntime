package net.neoforged.neoform.runtime.utils;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

public record JavaInstallationInformation(int majorVersion) {
    private static final Pattern JAVA_MAJOR_RELEASE_INFO = Pattern.compile("JAVA_VERSION=\"(\\d+)");
    private static final Pattern JAVA_MAJOR = Pattern.compile("\"(\\d+)");

    @Nullable
    public static JavaInstallationInformation fromExecutable(String executable) {
        var release = fromPathToExecutable(Path.of(executable));
        if (release != null) return release;
        return fromExecutableInvocation(executable);
    }

    @Nullable
    public static JavaInstallationInformation fromPathToExecutable(Path path) {
        var installationRoot = path.getParent();
        if (installationRoot == null) return null;
        var releaseInformation = path.resolve("release");
        try {
            var contents = Files.readString(releaseInformation);
            var matcher = JAVA_MAJOR_RELEASE_INFO.matcher(contents);
            if (matcher.find()) {
                return new JavaInstallationInformation(Integer.parseInt(matcher.group(1)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    public static JavaInstallationInformation fromExecutableInvocation(String executable) {
        var command = new ArrayList<String>();
        command.add(executable);
        command.add("-version");

        try {
            var output = Files.createTempFile("nfrt-java-version", ".log");

            var process = new ProcessBuilder()
                    .command(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            if (process.waitFor() == 0) {
                var firstLine = JAVA_MAJOR.matcher(Files.readAllLines(output).getFirst());
                if (firstLine.find()) {
                    return new JavaInstallationInformation(Integer.parseInt(firstLine.group(1)));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static JavaInstallationInformation fromRunningJVM() {
        return new JavaInstallationInformation(Runtime.version().feature());
    }
}
