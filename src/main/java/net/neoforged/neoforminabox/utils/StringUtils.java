package net.neoforged.neoforminabox.utils;

public final class StringUtils {
    private StringUtils() {
    }

    public static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
