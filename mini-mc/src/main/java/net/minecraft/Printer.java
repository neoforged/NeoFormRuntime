package net.minecraft;

class Printer {
    private final String message;

    Printer(String message) {
        this.message = message;
    }

    public void print() {
        System.out.println(message);
    }
}
