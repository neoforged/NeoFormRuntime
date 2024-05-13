package net.neoforged.neoform.runtime.utils;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class StringUtils {
    private StringUtils() {
    }

    public static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static String indent(String text, int indent) {
        var indentText = " ".repeat(indent);

        return Arrays.stream(text.split("\n"))
                .map(line -> indentText + line)
                .collect(Collectors.joining("\n"));
    }
}
