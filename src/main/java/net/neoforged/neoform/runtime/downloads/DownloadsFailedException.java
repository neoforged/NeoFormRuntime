package net.neoforged.neoform.runtime.downloads;

import java.util.List;

public class DownloadsFailedException extends Exception {
    private final List<Exception> errors;

    public DownloadsFailedException(List<Exception> errors) {
        this.errors = errors;
    }

    public List<Exception> getErrors() {
        return errors;
    }
}
