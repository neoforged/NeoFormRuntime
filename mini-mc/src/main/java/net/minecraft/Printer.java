package net.minecraft;

public class Printer {
    private final String message;

    public Printer(String message) {
        this.message = message;
    }

    public void print() {
        System.out.println(message);
    }
}
