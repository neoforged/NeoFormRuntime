package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.cache.CacheKey;
import net.neoforged.neoforminabox.graph.ExecutionNode;
import net.neoforged.neoforminabox.utils.AnsiColor;
import net.neoforged.neoforminabox.utils.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class CacheManager implements AutoCloseable {
    private final Path cacheDir;

    private boolean disabled;

    private boolean analyzeMisses;

    public CacheManager(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public boolean restoreOutputsFromCache(ExecutionNode node, CacheKey cacheKey, Map<String, Path> outputValues) throws IOException {
        var intermediateCacheDir = getIntermediateResultsDir();
        var cacheMarkerFile = getCacheMarkerFile(cacheKey);
        Files.createDirectories(intermediateCacheDir);
        if (Files.isRegularFile(cacheMarkerFile)) {
            // Try to rebuild output values from cache
            boolean complete = true;
            for (var entry : node.outputs().entrySet()) {
                var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
                var cachedFile = intermediateCacheDir.resolve(filename);
                if (Files.isRegularFile(cachedFile)) {
                    outputValues.put(entry.getKey(), cachedFile);
                } else {
                    System.err.println("Cache for " + node.id() + " is incomplete. Missing: " + filename);
                    outputValues.clear();
                    complete = false;
                    break;
                }
            }
            return complete;
        } else if (analyzeMisses) {
            analyzeCacheMiss(cacheKey);
        }
        return false;
    }

    public void saveOutputs(ExecutionNode node, CacheKey cacheKey, HashMap<String, Path> outputValues) throws IOException {
        var intermediateCacheDir = getIntermediateResultsDir();
        var finalOutputValues = new HashMap<String, Path>(outputValues.size());
        for (var entry : outputValues.entrySet()) {
            var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
            var cachedPath = intermediateCacheDir.resolve(filename);
            FileUtil.atomicMove(entry.getValue(), cachedPath);
            finalOutputValues.put(entry.getKey(), cachedPath);
        }
        outputValues.putAll(finalOutputValues);
        cacheKey.write(getCacheMarkerFile(cacheKey));
    }

    private Path getCacheMarkerFile(CacheKey cacheKey) {
        return getIntermediateResultsDir().resolve(cacheKey.type() + "_" + cacheKey.hashValue() + ".txt");
    }

    private Path getIntermediateResultsDir() {
        return getCacheDir().resolve("intermediate_results");
    }

    record CacheEntry(String filename, FileTime lastModified, CacheKey cacheKey) {
    }

    private void analyzeCacheMiss(CacheKey cacheKey) {
        var intermediateCacheDir = getIntermediateResultsDir();
        var cacheEntries = new ArrayList<>(getCacheEntries(intermediateCacheDir, cacheKey.type()));
        System.out.println("  " + cacheEntries.size() + " existing cache entries for " + cacheKey.type());

        // Calculate distances
        var deltasByCacheEntry = new IdentityHashMap<CacheEntry, List<CacheKey.Delta>>(cacheEntries.size());
        for (var cacheEntry : cacheEntries) {
            deltasByCacheEntry.put(cacheEntry, cacheKey.getDiff(cacheEntry.cacheKey()));
        }

        cacheEntries.sort(Comparator.comparingInt(value -> deltasByCacheEntry.get(value).size()));

        for (var cacheEntry : cacheEntries) {
            var diffCount = deltasByCacheEntry.get(cacheEntry).size();
            System.out.println("    " + cacheEntry.filename + " " + cacheEntry.lastModified + " " + diffCount + " deltas");
        }

        if (!cacheEntries.isEmpty()) {
            System.out.println("  Detailed delta for cache entry with best match:");
            for (var delta : deltasByCacheEntry.get(cacheEntries.getFirst())) {
                System.out.println("    " + AnsiColor.BLACK_UNDERLINED + delta.key() + AnsiColor.RESET);
                System.out.println(AnsiColor.BLACK_BRIGHT + "      New: " + AnsiColor.RESET + print(delta.ours()));
                System.out.println(AnsiColor.BLACK_BRIGHT + "      Old: " + AnsiColor.RESET + print(delta.theirs()));
            }
        }
    }

    private static String print(CacheKey.AnnotatedValue value) {
        if (value.annotation() != null) {
            return value.value() + AnsiColor.BLACK_BRIGHT + " (" + value.annotation() + ")" + AnsiColor.RESET;
        }
        return value.value();
    }

    private static List<CacheEntry> getCacheEntries(Path intermediateCacheDir, String type) {
        var filenamePattern = Pattern.compile(Pattern.quote(type) + "_[0-9a-f]+\\.txt");

        try (var stream = Files.list(intermediateCacheDir)) {
            return stream.filter(f -> filenamePattern.matcher(f.getFileName().toString()).matches()).map(p -> {
                try {
                    return new CacheEntry(p.getFileName().toString(), Files.getLastModifiedTime(p), CacheKey.read(p));
                } catch (Exception e) {
                    System.err.println("  Failed to read cache-key " + p + " for analysis");
                    return null;
                }
            }).filter(Objects::nonNull).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isAnalyzeMisses() {
        return analyzeMisses;
    }

    public void setAnalyzeMisses(boolean analyzeMisses) {
        this.analyzeMisses = analyzeMisses;
    }

    @Override
    public void close() throws Exception {
    }
}
