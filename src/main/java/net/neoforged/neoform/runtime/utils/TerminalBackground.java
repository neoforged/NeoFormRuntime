package net.neoforged.neoform.runtime.utils;

/**
 * This is inspired by https://github.com/dalance/termbg/tree/master
 */
final class TerminalBackground {
    private TerminalBackground() {
    }

    public static TerminalType detectType() {
        if ("vscode".equals(System.getenv("TERM_PROGRAM"))) {
            return TerminalType.XTERM;
        }

        if (System.getenv("INSIDE_EMACS") != null) {
            return TerminalType.EMACS;
        }

        // Windows Terminal is Xterm-compatible
        // https://github.com/microsoft/terminal/issues/3718
        if (System.getenv("WT_SESSION") != null) {
            return TerminalType.XTERM_COMPATIBLE;
        } else {
            return TerminalType.WINDOWS;
        }
    }

    public enum TerminalType {
        XTERM,
        XTERM_COMPATIBLE,
        EMACS,
        WINDOWS
    }
}
