package net.neoforged.neoform.runtime.engine;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interpolates tokens of the form {@code {token}} found in the argument lists of NeoForm functions.
 */
public final class NeoFormInterpolator {
    public static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([^}]+)}");

    public static void collectReferencedVariables(String text, Set<String> variables) {
        var matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }
}
