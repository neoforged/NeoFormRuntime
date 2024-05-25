package net.neoforged.neoform.runtime.utils;

// https://stackoverflow.com/questions/5762491/how-to-print-color-in-console-using-system-out-println
public enum AnsiColor {
    //Color end string, color reset
    RESET("0"),

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
