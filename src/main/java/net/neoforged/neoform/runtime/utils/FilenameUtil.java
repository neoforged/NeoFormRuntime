package net.neoforged.neoform.runtime.utils;


import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class FilenameUtil {
    private FilenameUtil() {
    }

    /**
     * The filename includes the period.
     */
    public static String getExtension(String path) {
        var lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));

        var potentialExtension = path.lastIndexOf('.');
        if (potentialExtension > lastSep) {
            // Check for a double extension like .tar.gz heuristically
            var doubleExtensionStart = path.lastIndexOf('.', potentialExtension - 1);
            // We only allow 3 chars maximum for the double extension
            if (doubleExtensionStart > lastSep && potentialExtension - doubleExtensionStart <= 4) {
                return path.substring(doubleExtensionStart);
            }

            return path.substring(potentialExtension);
        } else {
            return "";
        }
    }

    /**
     * This is more restrictive than it needs to be, but we only allow ASCII here, since the incoming text
     * is usually technical IDs and such.
     */
    public static String sanitizeForFilename(String text) {
        return text.replaceAll("[^a-zA-Z0-9._\\-,+'()\"!]+", "_");
    }

    @Nullable
    public static String longestCommonDirPrefix(List<String> paths) {
        var maxLength = paths.stream().mapToInt(s -> s.lastIndexOf('/')).min().orElse(-1);
        if (maxLength == -1) {
            return null;
        }

        var firstFile = paths.getFirst();
        for (int i = maxLength; i >= 1; i--) {
            if (firstFile.charAt(i) == '/') {
                for (var j = 0; j < i; j++) {
                    char ch = firstFile.charAt(j);
                    boolean allMatch = true;
                    for (var k = 1; k < paths.size(); k++) {
                        if (paths.get(k).charAt(j) != ch) {
                            allMatch = false;
                            break;
                        }
                    }

                    if (allMatch) {
                        return firstFile.substring(0, i);
                    }
                }
            }
        }

        return null;
    }
}
