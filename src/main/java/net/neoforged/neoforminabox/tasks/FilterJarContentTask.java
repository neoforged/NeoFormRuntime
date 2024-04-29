package net.neoforged.neoforminabox.tasks;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Copies a Jar file while applying a filename filter.
 */
public final class FilterJarContentTask {
    private final Path input;
    private final Path output;
    private final boolean whitelist;
    private final Set<String> filters;

    public FilterJarContentTask(Path input, Path output, boolean whitelist, Set<String> filters) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.whitelist = whitelist;
        this.filters = Set.copyOf(filters);
    }

    public void run() throws IOException {
        try (var is = new JarInputStream(new BufferedInputStream(Files.newInputStream(input)));
             var fout = new BufferedOutputStream(Files.newOutputStream(output));
             var os = new JarOutputStream(fout)) {

            // Ignore any entry that's not allowed
            JarEntry entry;
            while ((entry = is.getNextJarEntry()) != null) {
                if (!isEntryValid(entry, whitelist, filters)) {
                    continue;
                }
                os.putNextEntry(entry);
                is.transferTo(os);
                os.closeEntry();
            }
        }
    }

    private static boolean isEntryValid(JarEntry entry, boolean whitelist, Set<String> filters) {
        if (entry.isDirectory())
            return false;

        if (!filters.isEmpty()) {
            return filters.contains(entry.getName()) == whitelist;
        }

        return true;
    }
}
