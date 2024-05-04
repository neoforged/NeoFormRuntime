package net.neoforged.neoforminabox.cache;

import net.neoforged.neoforminabox.cli.FileHashService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class CacheKeyBuilder {
    private final FileHashService fileHashService;

    private final Map<String, String> components = new HashMap<>();

    public CacheKeyBuilder(FileHashService fileHashService) {
        this.fileHashService = fileHashService;
    }

    public Map<String, String> getComponents() {
        return components;
    }

    public void addPath(String component, Path resultPath) {
        String hashValue;
        try {
            hashValue = fileHashService.getHashValue(resultPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        add(component, hashValue);
    }

    public void add(String component, String text) {
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        components.put(component, text);
    }

    public void add(String component, Collection<?> objects) {
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        components.put(component, objects.stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
    }

    public String buildCacheKey() {
        return components.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    public FileHashService getFileHashService() {
        return fileHashService;
    }
}
