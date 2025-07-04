package net.neoforged.neoform.runtime.utils;

import java.util.Map;

public final class Logger {
    // Replacement Map for Emojis
    private static final Map<Character, String> EMOJI_MAP = Map.of(
            '↓', "DL   ",
            '♻', "REUSE",
            '✓', "DONE ",
            '↳', "RUN  "
    );

    public static boolean NO_COLOR;
    public static boolean NO_EMOJIS;
    public static boolean PRINT_THREAD;
    private static IndeterminateSpinner spinner;

    public static Logger create() {
        return new Logger();
    }

    public void warn(String message) {
        println("  " + AnsiColor.YELLOW + "WARNING: " + AnsiColor.RESET + message);
    }

    public void println(String text) {
        closeSpinner();

        // Print a process-id and thread-id to help identify concurrently running instances of NFRT in verbose mode
        if (PRINT_THREAD) {
            System.out.print(
                    cleanText(AnsiColor.MUTED + "[" + ProcessHandle.current().pid() + ":" + Thread.currentThread().threadId() + "] " + AnsiColor.RESET)
            );
        }

        System.out.println(cleanText(text));
    }

    private void closeSpinner() {
        if (spinner != null) {
            spinner.end();
            spinner = null;
            System.out.println(); // End line
        }
    }

    public IndeterminateSpinner spinner(String message) {
        closeSpinner();

        System.out.print(cleanText(message));
        return spinner = new IndeterminateSpinner();
    }

    private static String cleanText(String text) {
        if (!NO_COLOR && !NO_EMOJIS) {
            return text;
        }

        var result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            var ch = text.charAt(i);
            // Strip ANSI Escape Sequences
            if (NO_COLOR && ch == '\033') {
                i++;
                for (; i < text.length() && text.charAt(i) != 'm'; i++) {
                }
            } else if (NO_EMOJIS && ch >= 0x7f) {
                result.append(EMOJI_MAP.getOrDefault(ch, "."));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * Shows a very basic animation when the user has to wait for an operation of indeterminate length.
     */
    public static class IndeterminateSpinner {
        String[] spinners = {"", ".", "..", "..."};
        String lastTextPrinted = "";
        int spinnerIndex = 0;

        public IndeterminateSpinner() {
            tick();
        }

        public void tick() {
            if (!lastTextPrinted.isEmpty()) {
                System.out.print("\b".repeat(lastTextPrinted.length())); // clear the last spinner
            }
            lastTextPrinted = spinners[++spinnerIndex % spinners.length];
            System.out.print(lastTextPrinted);
        }

        public void end() {
            if (spinner == this) {
                System.out.print("\b".repeat(lastTextPrinted.length())); // clear the last spinner
                lastTextPrinted = "";
                spinner = null;
            }
        }
    }
}
