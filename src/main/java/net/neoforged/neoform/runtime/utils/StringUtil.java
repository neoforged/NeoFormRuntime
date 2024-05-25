package net.neoforged.neoform.runtime.utils;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public final class StringUtil {
    private StringUtil() {
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

    private static final String[] FILE_SIZE_SUFFIXES = {"B", "KiB", "MiB", "GiB", "TiB"};

    /**
     * Format a file or transfer size.
     */
    public static String formatBytes(long size) {
        long prevSize = size * 1024;
        for (var fileSizeSuffix : FILE_SIZE_SUFFIXES) {
            // If the size at this suffix is just 1 digit, we include a fractional digit from the previous suffix
            if (size < 10) {
                var df = new DecimalFormat("###.#", new DecimalFormatSymbols(Locale.ROOT));
                df.setMinimumFractionDigits(0);
                df.setMaximumFractionDigits(1);
                df.setRoundingMode(RoundingMode.DOWN);
                return df.format(prevSize / 1024.0) + " " + fileSizeSuffix;
            } else if (size < 1000) {
                return size + " " + fileSizeSuffix;
            }
            prevSize = size;
            size /= 1024;
        }
        return size * 1024 + " " + FILE_SIZE_SUFFIXES[FILE_SIZE_SUFFIXES.length - 1];
    }

    /**
     * Format a java.time duration in a human-readable format.
     */
    public static String formatDuration(Duration duration) {
        var result = new StringBuilder();

        if (duration.toDaysPart() > 0) {
            result.append(duration.toDaysPart()).append('d');
        }

        if (duration.toHoursPart() > 0) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(duration.toHoursPart()).append('h');
        }

        if (duration.toMinutesPart() > 0) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(duration.toMinutesPart()).append('m');
        }

        if (duration.toSecondsPart() > 0) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(duration.toSecondsPart()).append('s');
        }

        return result.toString();
    }
}
