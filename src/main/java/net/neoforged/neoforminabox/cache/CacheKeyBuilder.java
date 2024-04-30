package net.neoforged.neoforminabox.cache;

import net.neoforged.neoforminabox.cli.FileHashService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Comparator;
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

    public void add(String component, String hashValue) {
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        components.put(component, hashValue);
    }

    public String buildCacheKey() {
        return components.entrySet()
                .stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }
}
