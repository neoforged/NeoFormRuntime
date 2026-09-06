package net.neoforged.neoform.runtime.utils;

// https://stackoverflow.com/questions/5762491/how-to-print-color-in-console-using-system-out-println
public enum AnsiColor {
    // 23 to disable italic (0 doesn't do it in IDEA); 0 to reset everything (in theory)
    RESET("23;0"),

    YELLOW("33"),

    BOLD("1"),
    ITALIC("3"),
    UNDERLINE("4"),

    MUTED("0;2;3"),

    BRIGHT_GREEN("92");

    private final String code;

    AnsiColor(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return "\033[" + code + "m";
    }
}
