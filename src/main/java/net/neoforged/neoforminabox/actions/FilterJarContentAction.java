package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.engine.ProcessingEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Copies a Jar file while applying a filename filter.
 */
public final class FilterJarContentAction extends BuiltInAction {
    private final boolean whitelist;
    private final Set<String> filters;

    public FilterJarContentAction() {
        this(true, Set.of());
    }

    public FilterJarContentAction(boolean whitelist, Set<String> filters) {
        this.whitelist = whitelist;
        this.filters = Set.copyOf(filters);
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputJar = environment.getRequiredInputPath("input");
        var outputJar = environment.getOutputPath("output");

        try (var is = new JarInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
             var fout = new BufferedOutputStream(Files.newOutputStream(outputJar));
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
