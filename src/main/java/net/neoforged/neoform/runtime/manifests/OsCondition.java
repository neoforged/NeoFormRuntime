package net.neoforged.neoform.runtime.manifests;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public record OsCondition(@Nullable String name, @Nullable String version, @Nullable String arch) {
    public boolean nameMatches() {
        if (name == null) {
            return true;
        }
        var osName = System.getProperty("os.name");
        // The following matches the logic in Apache Commons Lang 3 SystemUtils
        return switch (name) {
            case "linux" -> osName.startsWith("Linux") || osName.startsWith("LINUX");
            case "osx" -> osName.startsWith("Mac OS X");
            case "windows" -> osName.startsWith("Windows");
            default -> false;
        };
    }

    public boolean versionMatches() {
        return version == null || Pattern.compile(version).matcher(System.getProperty("os.version")).find();
    }

    public boolean archMatches() {
        return arch == null || Pattern.compile(arch).matcher(System.getProperty("os.arch")).find();
    }

    public boolean platformMatches() {
        return nameMatches() && versionMatches() && archMatches();
    }
}
