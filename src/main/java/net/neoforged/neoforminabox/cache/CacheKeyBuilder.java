package net.neoforged.neoforminabox.cache;

import net.neoforged.neoforminabox.cli.FileHashService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public void addPaths(String component, Collection<Path> resultPath) {
        record HashedPath(String path, String hashValue) {
        }

        add(component, resultPath.parallelStream().map(path -> {
            try {
                return new HashedPath(path.toString(), fileHashService.getHashValue(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList());
    }

    public void addPath(String component, Path path) {
        String hashValue;
        try {
            hashValue = fileHashService.getHashValue(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Prettify the path
        var userHome = Paths.get(System.getProperty("user.home"));
        String prettifiedPath;
        if (path.startsWith(userHome)) {
            prettifiedPath = "~/" + userHome.relativize(path).toString().replace('\\', '/');
        } else {
            prettifiedPath = path.toString();
        }

        add(component, prettifiedPath + " [" + hashValue + "]");
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
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    public FileHashService getFileHashService() {
        return fileHashService;
    }
}
