package net.neoforged.neoform.runtime.utils;

public final class Logger {
    private static IndeterminateSpinner spinner;

    public static Logger create() {
        return new Logger();
    }

    public void println(String text) {
        closeSpinner();

        System.out.println(text);
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

        System.out.print(message);
        return spinner = new IndeterminateSpinner();
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
