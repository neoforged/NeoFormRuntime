package net.neoforged.neoforminabox.utils;

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
}
