package net.neoforged.neoform.runtime.utils;

import javax.swing.plaf.SpinnerUI;

public final class Logger {
    private final LoggerCategory category;
    private static IndeterminateSpinner spinner;

    public Logger(LoggerCategory category) {
        this.category = category;
    }

    public static Logger create(LoggerCategory category) {
        return new Logger(category);
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

    public static class IndeterminateSpinner {
        String[] spinners = {"   ", ".  ", ".. ", "..."};
        int spinnerIndex = 0;

        public IndeterminateSpinner() {
            System.out.println(spinners[spinnerIndex]);
        }

        public void tick() {
            System.out.print("\b\b\b"); // clear the last spinner
            System.out.print(spinners[++spinnerIndex % spinners.length]);
        }

        public void end() {
            if (spinner == this) {
                System.out.print("\b\b\b"); // clear the last spinner
                spinner = null;
            }
        }
    }
}
