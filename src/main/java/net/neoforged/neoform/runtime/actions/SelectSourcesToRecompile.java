package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class SelectSourcesToRecompile extends BuiltInAction {
    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var originalSources = environment.getRequiredInputPath("originalSources");
        var originalClasses = environment.getRequiredInputPath("originalClasses");
        var transformedSources = environment.getRequiredInputPath("transformedSources");

        var unchangedClasses = environment.getOutputPath("unchangedClasses");
        var changedSourcesOnly = environment.getOutputPath("changedSourcesOnly");

        // Read all original sources to memory
        var originalSourcesContents = new HashMap<String, byte[]>();
        try (var zf = new ZipFile(originalSources.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (var is = zf.getInputStream(entry)) {
                    originalSourcesContents.put(entry.getName(), is.readAllBytes());
                }
            }
        }

        // Copy unchanged sources to the output
        var changedSourcePaths = new HashSet<String>();
        try (var os = Files.newOutputStream(changedSourcesOnly);
             var zos = new ZipOutputStream(os)) {

            try (var transformedZip = new ZipFile(transformedSources.toFile())) {
                var entries = transformedZip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    try (var is = transformedZip.getInputStream(entry)) {
                        var data = is.readAllBytes();

                        if (!Arrays.equals(data, originalSourcesContents.get(entry.getName()))) {
                            changedSourcePaths.add(entry.getName());
                            // Copy to output
                            var copiedEntry = new ZipEntry(entry.getName());
                            copiedEntry.setMethod(entry.getMethod());
                            zos.putNextEntry(copiedEntry);
                            zos.write(data);
                            zos.closeEntry();
                        }
                    }
                }
            }
        }

        // Copy unchanged classes, by first copying the whole zip then deleting unwanted entries using NIO.
        // This is faster than working with ZipFile streams which inflate and deflate all the entries.
        Files.copy(originalClasses, unchangedClasses);
        try (var zfs = FileSystems.newFileSystem(unchangedClasses, Map.of("create", "false"))) {
            try (var originalZip = new ZipFile(originalClasses.toFile())) {
                var entries = originalZip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    // Remove trailing .class
                    var sourceName = entry.getName();
                    if (sourceName.endsWith(".class")) {
                        sourceName = sourceName.substring(0, sourceName.length() - 6);
                    }
                    // Remove inner class suffix
                    sourceName = sourceName.split("\\$")[0];
                    // Add trailing .java
                    sourceName += ".java";

                    if (!changedSourcePaths.contains(sourceName)) {
                        // Skip this entry
                        continue;
                    }

                    // Delete!
                    Files.delete(zfs.getPath(entry.getName()));
                }
            }
        }
    }
}
